package net.bitsar.sonify.core.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.bitsar.sonify.core.event.ThreadSlice;
import net.bitsar.sonify.core.event.TickEvent;

/**
 * Builds one {@link TickEvent} by merging the exact MXBean readings with the Flight Recorder
 * window, and ranks the threads worth showing in the per-thread lane.
 */
public final class TickAssembler {

    private final MetricsAccumulator accumulator;
    private final Supplier<JvmVitals.Sample> vitals;
    private final Supplier<AllocationSampler.Sample> allocation;
    private final AllocationRateMeter allocationRate;
    private final int maxThreadSlices;
    private final LongSupplier wallClock;

    public TickAssembler(MetricsAccumulator accumulator,
                         JvmVitals vitals,
                         AllocationSampler allocation,
                         int maxThreadSlices,
                         long allocationWindowMillis) {
        this(accumulator, vitals::sample, allocation::sample, maxThreadSlices,
                allocationWindowMillis, System::currentTimeMillis);
    }

    TickAssembler(MetricsAccumulator accumulator,
                  Supplier<JvmVitals.Sample> vitals,
                  Supplier<AllocationSampler.Sample> allocation,
                  int maxThreadSlices,
                  long allocationWindowMillis,
                  LongSupplier wallClock) {
        this.accumulator = accumulator;
        this.vitals = vitals;
        this.allocation = allocation;
        this.allocationRate = new AllocationRateMeter(allocationWindowMillis);
        this.maxThreadSlices = maxThreadSlices;
        this.wallClock = wallClock;
    }

    public TickEvent assemble() {
        MetricsAccumulator.Snapshot window = accumulator.drain();
        JvmVitals.Sample reading = vitals.get();
        AllocationSampler.Sample allocated = allocation.get();
        long wallMillis = wallClock.getAsLong();

        double allocMBps = allocated.supported()
                ? allocationRate.megabytesPerSecond(wallMillis, allocated.cumulativeBytes())
                : 0d;

        return new TickEvent(wallMillis,
                reading.heapUsed(),
                reading.heapCommitted(),
                reading.heapMax(),
                allocMBps,
                reading.processCpu(),
                reading.machineCpu(),
                reading.threadCount(),
                window.contendedThreads(),
                window.parkedThreads(),
                window.ioWaitThreads(),
                reading.blockedThreads(),
                reading.waitingThreads(),
                rankThreads(allocated.bytesByThread(), window.byThread()));
    }

    /**
     * Picks the threads worth a row in the UI: blocked threads first, since those are the ones a
     * person is looking for, then the heaviest allocators.
     */
    private List<ThreadSlice> rankThreads(Map<String, Long> allocatedByThread,
                                          Map<String, MetricsAccumulator.Counts> blockingByThread) {
        Set<String> names = new HashSet<>(allocatedByThread.keySet());
        names.addAll(blockingByThread.keySet());

        List<ThreadSlice> slices = new ArrayList<>(names.size());
        for (String name : names) {
            long allocBytes = allocatedByThread.getOrDefault(name, 0L);
            MetricsAccumulator.Counts counts = blockingByThread.get(name);
            int blocks = counts == null ? 0 : counts.blocks();
            int parks = counts == null ? 0 : counts.parks();
            int ioWaits = counts == null ? 0 : counts.ioWaits();
            slices.add(new ThreadSlice(name, stateOf(blocks, parks, ioWaits), allocBytes, blocks, parks, ioWaits));
        }

        slices.sort(Comparator
                .comparingInt((ThreadSlice slice) -> slice.blocks() > 0 ? 0 : slice.ioWaits() > 0 ? 1 : 2)
                .thenComparing(slice -> -slice.allocBytes())
                .thenComparing(ThreadSlice::name));
        return slices.size() > maxThreadSlices ? List.copyOf(slices.subList(0, maxThreadSlices)) : slices;
    }

    /**
     * Contention first, then waiting on the network, then parking.
     *
     * <p>A thread in a socket read is a request that cannot finish, while a parked thread is
     * usually a pool thread with nothing to do, so waiting outranks parking.
     */
    private static String stateOf(int blocks, int parks, int ioWaits) {
        if (blocks > 0) {
            return ThreadSlice.CONTENDED;
        }
        if (ioWaits > 0) {
            return ThreadSlice.WAITING;
        }
        return parks > 0 ? ThreadSlice.PARKED : ThreadSlice.RUNNING;
    }
}
