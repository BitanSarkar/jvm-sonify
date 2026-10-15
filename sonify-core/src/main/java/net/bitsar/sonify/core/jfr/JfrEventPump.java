package net.bitsar.sonify.core.jfr;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;
import net.bitsar.sonify.core.SonifyConfig;
import net.bitsar.sonify.core.event.ContentionEvent;
import net.bitsar.sonify.core.event.GcEvent;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.metrics.MetricsAccumulator;

/**
 * Owns the single in-process {@link RecordingStream} for the whole JVM.
 *
 * <p>Only subscribes to what nothing else can measure: collection pauses, monitor contention and
 * parking. Heap, CPU, thread count and allocation all come from the platform MXBeans, exactly and
 * without Flight Recorder's batching delay.
 *
 * <p>One stream serves every connected browser. Stack traces are switched off on all
 * subscriptions because nothing here reads them and capturing them is the expensive part of JFR.
 *
 * <p>Nothing in this class is allowed to disturb the application being measured: if JFR cannot
 * start, {@link #start()} reports false after logging a single warning, and the broker carries on
 * publishing the MXBean-derived vitals. Each handler swallows its own failures for the same reason.
 */
public final class JfrEventPump implements AutoCloseable {

    private static final Logger LOG = System.getLogger(JfrEventPump.class.getName());
    private static final int GC_CORRELATION_WINDOW = 64;

    private final SonifyConfig config;
    private final MetricsAccumulator accumulator;
    private final Consumer<SonifyEvent> sink;

    /**
     * Heap summaries arrive just before their collection's event, so the two are correlated by
     * gcId. Only the JFR stream thread touches this map, which is why it needs no synchronisation.
     */
    private final Map<Integer, long[]> heapByGcId = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, long[]> eldest) {
            return size() > GC_CORRELATION_WINDOW;
        }
    };

    private volatile RecordingStream stream;
    private volatile boolean available;

    public JfrEventPump(SonifyConfig config, MetricsAccumulator accumulator, Consumer<SonifyEvent> sink) {
        this.config = config;
        this.accumulator = accumulator;
        this.sink = sink;
    }

    /** Starts streaming. Returns false if this JVM cannot provide Flight Recorder data. */
    public boolean start() {
        try {
            RecordingStream starting = new RecordingStream();
            starting.enable("jdk.GarbageCollection");
            starting.enable("jdk.GCHeapSummary");
            starting.enable("jdk.JavaMonitorEnter")
                    .withThreshold(config.contentionThreshold())
                    .withoutStackTrace();
            starting.enable("jdk.ThreadPark")
                    .withThreshold(config.parkThreshold())
                    .withoutStackTrace();

            starting.onEvent("jdk.GCHeapSummary", this::onHeapSummary);
            starting.onEvent("jdk.GarbageCollection", this::onGarbageCollection);
            starting.onEvent("jdk.JavaMonitorEnter", this::onMonitorEnter);
            starting.onEvent("jdk.ThreadPark", this::onThreadPark);

            starting.startAsync();
            this.stream = starting;
            this.available = true;
            LOG.log(Level.DEBUG, "JVM Sonify: JFR recording stream started");
            return true;
        } catch (Throwable unavailable) {
            this.available = false;
            LOG.log(Level.WARNING, "JVM Sonify: Flight Recorder is unavailable, "
                    + "continuing with heap, thread and CPU readings only", unavailable);
            return false;
        }
    }

    public boolean available() {
        return available;
    }

    @Override
    public void close() {
        RecordingStream closing = this.stream;
        this.stream = null;
        this.available = false;
        if (closing != null) {
            try {
                closing.close();
            } catch (Throwable ignored) {
                // Shutting down: a failure here must not propagate into the host application.
            }
        }
    }

    private void onHeapSummary(RecordedEvent event) {
        try {
            int gcId = event.getInt("gcId");
            long used = event.getLong("heapUsed");
            long committed = committedOf(event);
            long[] slot = heapByGcId.computeIfAbsent(gcId, key -> new long[] {-1L, -1L, -1L});
            if ("Before GC".equals(event.getString("when"))) {
                slot[0] = used;
            } else {
                slot[1] = used;
                slot[2] = committed;
            }
        } catch (Throwable ignored) {
            // A JDK that shapes this event differently simply yields no heap figures.
        }
    }

    private static long committedOf(RecordedEvent event) {
        Object space = event.getValue("heapSpace");
        return space instanceof RecordedObject virtualSpace ? virtualSpace.getLong("committedSize") : -1L;
    }

    private void onGarbageCollection(RecordedEvent event) {
        try {
            long[] heap = heapByGcId.remove(event.getInt("gcId"));
            Long usedAfter = heap == null || heap[1] < 0 ? null : heap[1];
            Long committedAfter = heap == null || heap[2] < 0 ? null : heap[2];
            Long reclaimed = heap == null || heap[0] < 0 || heap[1] < 0 ? null : Math.max(0L, heap[0] - heap[1]);

            sink.accept(new GcEvent(eventTime(event),
                    GcNameFormatter.pretty(event.getString("name")),
                    event.getString("cause"),
                    millis(event, "sumOfPauses"),
                    millis(event, "duration"),
                    usedAfter,
                    committedAfter,
                    reclaimed));
        } catch (Throwable ignored) {
            // Drop this one collection rather than risk the stream thread.
        }
    }

    private void onMonitorEnter(RecordedEvent event) {
        try {
            String thread = threadName(event);
            accumulator.recordContention(thread);
            RecordedClass monitor = event.getClass("monitorClass");
            sink.accept(new ContentionEvent(eventTime(event),
                    thread,
                    ClassNameShortener.shorten(monitor == null ? null : monitor.getName()),
                    millis(event, "duration")));
        } catch (Throwable ignored) {
            // Drop this one event.
        }
    }

    private void onThreadPark(RecordedEvent event) {
        try {
            accumulator.recordPark(threadName(event));
        } catch (Throwable ignored) {
            // Drop this one event.
        }
    }

    /**
     * The moment the event actually occurred.
     *
     * <p>Flight Recorder flushes in batches of roughly a second, so dozens of collections can be
     * delivered in the same instant. Stamping them with the delivery time would collapse a second
     * of activity into one spike on the timeline and one simultaneous pile of sound; the event's
     * own start time keeps them spread out as they really happened.
     */
    private static long eventTime(RecordedEvent event) {
        try {
            Instant startTime = event.getStartTime();
            return startTime == null ? System.currentTimeMillis() : startTime.toEpochMilli();
        } catch (Throwable unavailable) {
            return System.currentTimeMillis();
        }
    }

    private static String threadName(RecordedEvent event) {
        RecordedThread thread = event.getThread("eventThread");
        return thread == null ? "unknown" : thread.getJavaName();
    }

    private static double millis(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return 0d;
        }
        return event.getDuration(field).toNanos() / 1_000_000d;
    }
}
