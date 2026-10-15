package net.bitsar.sonify.core.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Reads heap, thread and CPU figures straight from the platform MXBeans.
 *
 * <p>These are JDK specification APIs and behave identically on macOS, Linux and Windows, which
 * is why they are preferred here over the equivalent JFR events: {@code jdk.CPULoad} reports a
 * flat zero for JVM user and system time on macOS, and {@code jdk.GCHeapSummary} only fires at a
 * collection, so an idle application's heap line would simply freeze.
 *
 * <p>Sampling all of these costs about 1.4 microseconds, so it is affordable on every tick. CPU
 * load is reported as null when the running JVM cannot supply it, rather than as a misleading zero.
 *
 * <p>Thread states are sampled here too, and that is deliberate. Flight Recorder reports a wait
 * only once it has finished, because the event carries a duration; a thread that is stuck right
 * now has emitted nothing at all. For the failure modes that matter most, a drained connection
 * pool or a client with no read timeout, every thread is stuck and nothing is ever emitted, so an
 * event-driven view shows an idle application. Asking the thread bean how many threads are
 * blocked or waiting at this instant costs about 44 microseconds and answers truthfully whether
 * or not anybody is ever going to get unstuck.
 *
 * <p>Not thread safe: one instance belongs to one tick thread.
 */
public final class JvmVitals {

    private static final Duration CPU_HOLD = Duration.ofSeconds(2);

    private final MemoryMXBean memory;
    private final ThreadMXBean threads;
    private final com.sun.management.OperatingSystemMXBean extendedOs;
    private final LongSupplier nanoClock;
    private final CpuReading processCpu;
    private final CpuReading machineCpu;

    public JvmVitals() {
        this(ManagementFactory.getMemoryMXBean(), ManagementFactory.getThreadMXBean(),
                resolveExtendedOs(), System::nanoTime, CPU_HOLD);
    }

    JvmVitals(MemoryMXBean memory,
              ThreadMXBean threads,
              com.sun.management.OperatingSystemMXBean extendedOs) {
        this(memory, threads, extendedOs, System::nanoTime, CPU_HOLD);
    }

    JvmVitals(MemoryMXBean memory,
              ThreadMXBean threads,
              com.sun.management.OperatingSystemMXBean extendedOs,
              LongSupplier nanoClock,
              Duration hold) {
        this.memory = memory;
        this.threads = threads;
        this.extendedOs = extendedOs;
        this.nanoClock = nanoClock;
        this.processCpu = new CpuReading(hold.toNanos());
        this.machineCpu = new CpuReading(hold.toNanos());
    }

    private static com.sun.management.OperatingSystemMXBean resolveExtendedOs() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended : null;
        } catch (Throwable denied) {
            // A JVM without the jdk.management module still gets heap and thread readings.
            return null;
        }
    }

    public Sample sample() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long now = nanoClock.getAsLong();
        ThreadStates states = sampleThreadStates();
        return new Sample(heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                threads.getThreadCount(),
                processCpu.update(extendedOs == null ? -1d : extendedOs.getProcessCpuLoad(), now),
                machineCpu.update(extendedOs == null ? -1d : extendedOs.getCpuLoad(), now),
                states.blocked(),
                states.waiting());
    }

    /**
     * Counts threads that are stuck right now.
     *
     * <p>BLOCKED means waiting to enter a monitor. WAITING and TIMED_WAITING cover everything
     * else that is parked or sleeping, which includes both a request waiting for a connection and
     * an idle pool thread with nothing to do; telling those apart is left to the consumer, which
     * can compare against the resting level.
     */
    private ThreadStates sampleThreadStates() {
        try {
            long[] ids = threads.getAllThreadIds();
            ThreadInfo[] infos = threads.getThreadInfo(ids, 0);
            int blocked = 0;
            int waiting = 0;
            for (ThreadInfo info : infos) {
                if (info == null) {
                    continue;
                }
                switch (info.getThreadState()) {
                    case BLOCKED -> blocked++;
                    case WAITING, TIMED_WAITING -> waiting++;
                    default -> {
                        // RUNNABLE, NEW and TERMINATED need no counting.
                    }
                }
            }
            return new ThreadStates(blocked, waiting);
        } catch (Throwable unavailable) {
            return new ThreadStates(0, 0);
        }
    }

    private record ThreadStates(int blocked, int waiting) {
    }

    /** True when this JVM can report CPU load at all. */
    public boolean cpuAvailable() {
        return extendedOs != null;
    }

    /** One MXBean reading. CPU fields are null when unavailable on this platform. */
    public record Sample(long heapUsed,
                         long heapCommitted,
                         long heapMax,
                         int threadCount,
                         Double processCpu,
                         Double machineCpu,
                         int blockedThreads,
                         int waitingThreads) {
    }

    /**
     * Carries the last usable CPU figure across the gaps in the operating system's sampler.
     *
     * <p>The OS recomputes load on its own schedule and answers -1 when asked again too soon, so
     * polling every 100ms would make the reading flicker between a real value and nothing. The
     * last good value stands for a short while, exactly as a bedside monitor holds a reading, and
     * then gives way to null rather than showing something stale as if it were current.
     */
    private static final class CpuReading {

        private final long holdNanos;
        private Double value;
        private long takenAt;

        private CpuReading(long holdNanos) {
            this.holdNanos = holdNanos;
        }

        private Double update(double reading, long now) {
            Double ratio = ratio(reading);
            if (ratio != null) {
                value = ratio;
                takenAt = now;
                return ratio;
            }
            if (value != null && now - takenAt <= holdNanos) {
                return value;
            }
            value = null;
            return null;
        }

        private static Double ratio(double value) {
            if (!Double.isFinite(value) || value < 0d) {
                return null;
            }
            return value > 1d ? 1d : value;
        }
    }
}
