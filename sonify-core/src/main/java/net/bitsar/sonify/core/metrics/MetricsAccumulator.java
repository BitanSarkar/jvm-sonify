package net.bitsar.sonify.core.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects the Flight Recorder observations that only JFR can provide: which threads blocked on a
 * monitor, which parked, and which sat in a socket read waiting for something on the network.
 *
 * <p>Allocation is deliberately not collected here. It comes from the platform thread bean
 * instead, exactly rather than by sampling; see {@link AllocationSampler}.
 *
 * <p>Every {@code record*} method is called from the JFR stream thread and stays lock free;
 * {@link #drain()} is called from the tick thread and starts a new window. The number of tracked
 * threads is capped so that an application churning through short-lived threads cannot grow this
 * map without bound.
 */
public final class MetricsAccumulator {

    private static final int MAX_TRACKED_THREADS = 256;

    private final Map<String, Blocking> blockingByThread = new ConcurrentHashMap<>();

    public void recordContention(String threadName) {
        Blocking tracked = track(threadName);
        if (tracked != null) {
            tracked.blocks.increment();
        }
    }

    public void recordPark(String threadName) {
        Blocking tracked = track(threadName);
        if (tracked != null) {
            tracked.parks.increment();
        }
    }

    /** A thread that spent longer than the threshold blocked in a socket read. */
    public void recordIoWait(String threadName) {
        Blocking tracked = track(threadName);
        if (tracked != null) {
            tracked.ioWaits.increment();
        }
    }

    private Blocking track(String threadName) {
        String name = threadName == null || threadName.isEmpty() ? "unknown" : threadName;
        Blocking existing = blockingByThread.get(name);
        if (existing != null) {
            return existing;
        }
        if (blockingByThread.size() >= MAX_TRACKED_THREADS) {
            return null;
        }
        return blockingByThread.computeIfAbsent(name, key -> new Blocking());
    }

    /** Returns what has accumulated since the last call and starts a new window. */
    public Snapshot drain() {
        Map<String, Counts> counts = new LinkedHashMap<>();
        int contended = 0;
        int parked = 0;
        int waiting = 0;
        for (Map.Entry<String, Blocking> entry : blockingByThread.entrySet()) {
            int blocks = entry.getValue().blocks.intValue();
            int parks = entry.getValue().parks.intValue();
            int ioWaits = entry.getValue().ioWaits.intValue();
            if (blocks == 0 && parks == 0 && ioWaits == 0) {
                continue;
            }
            if (blocks > 0) {
                contended++;
            }
            if (parks > 0) {
                parked++;
            }
            if (ioWaits > 0) {
                waiting++;
            }
            counts.put(entry.getKey(), new Counts(blocks, parks, ioWaits));
        }
        blockingByThread.clear();
        return new Snapshot(contended, parked, waiting, Map.copyOf(counts));
    }

    /**
     * One drained window.
     *
     * <p>Counts are of distinct threads rather than events: "three threads were contended" says
     * more about an application's health than "three monitor-enter events fired".
     */
    public record Snapshot(int contendedThreads,
                           int parkedThreads,
                           int ioWaitThreads,
                           Map<String, Counts> byThread) {
    }

    /** How often one thread blocked, parked or waited on the network during a window. */
    public record Counts(int blocks, int parks, int ioWaits) {
    }

    private static final class Blocking {
        private final LongAdder blocks = new LongAdder();
        private final LongAdder parks = new LongAdder();
        private final LongAdder ioWaits = new LongAdder();
    }
}
