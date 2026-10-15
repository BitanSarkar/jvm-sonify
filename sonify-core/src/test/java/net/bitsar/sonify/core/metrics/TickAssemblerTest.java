package net.bitsar.sonify.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.bitsar.sonify.core.event.ThreadSlice;
import net.bitsar.sonify.core.event.TickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TickAssemblerTest {

    private static final long MB = 1024L * 1024L;

    private final MetricsAccumulator accumulator = new MetricsAccumulator();
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
    private final AtomicReference<JvmVitals.Sample> vitals =
            new AtomicReference<>(reading(0.37d));
    private final AtomicReference<AllocationSampler.Sample> allocation =
            new AtomicReference<>(new AllocationSampler.Sample(true, 0L, Map.of()));

    private TickAssembler assembler() {
        return new TickAssembler(accumulator, vitals::get, allocation::get, 16, 2000L, wall::get);
    }

    private static JvmVitals.Sample reading(Double cpu) {
        return new JvmVitals.Sample(400L * MB, 1024L * MB, 4096L * MB, 42, cpu, 0.6d);
    }

    @Test
    @DisplayName("a tick carries the MXBean reading and the JFR window together")
    void combinesBothSources() {
        TickAssembler assembler = assembler();
        accumulator.recordContention("worker");
        allocation.set(new AllocationSampler.Sample(true, 22 * MB, Map.of("worker", 22 * MB)));

        TickEvent tick = assembler.assemble();

        assertEquals(1_700_000_000_000L, tick.t());
        assertEquals(400L * MB, tick.heapUsed());
        assertEquals(1024L * MB, tick.heapCommitted());
        assertEquals(4096L * MB, tick.heapMax());
        assertEquals(42, tick.threads());
        assertEquals(0.37d, tick.cpu());
        assertEquals(0.6d, tick.machineCpu());
        assertEquals(1, tick.contended());
        assertEquals(1, tick.threadSlices().size());
        assertEquals(ThreadSlice.CONTENDED, tick.threadSlices().get(0).state());
        assertEquals(22 * MB, tick.threadSlices().get(0).allocBytes());
    }

    @Test
    @DisplayName("the rate is averaged over the window, so a JFR batch is not extrapolated")
    void rateIsAveragedOverTheWindow() {
        TickAssembler assembler = assembler();
        long cumulative = 0;
        double worst = 0;

        // 100MB/s, ticking every 100ms for four seconds.
        for (int tick = 1; tick <= 40; tick++) {
            cumulative += 10 * MB;
            allocation.set(new AllocationSampler.Sample(true, cumulative, Map.of()));
            wall.set(1_700_000_000_000L + tick * 100L);
            worst = Math.max(worst, assembler.assemble().allocMBps());
        }

        assertTrue(worst > 90d && worst < 110d, "expected about 100MB/s, worst reading was " + worst);
    }

    @Test
    @DisplayName("an idle window reports a zero rate and no thread rows")
    void idleWindow() {
        TickAssembler assembler = assembler();
        wall.addAndGet(100L);

        TickEvent tick = assembler.assemble();

        assertEquals(0d, tick.allocMBps());
        assertEquals(0, tick.contended());
        assertEquals(0, tick.parked());
        assertEquals(List.of(), tick.threadSlices());
    }

    @Test
    @DisplayName("an unavailable CPU reading stays null all the way through")
    void missingCpuStaysNull() {
        vitals.set(new JvmVitals.Sample(1L, 2L, 3L, 4, null, null));

        TickEvent tick = assembler().assemble();

        assertNull(tick.cpu());
        assertNull(tick.machineCpu());
    }

    @Test
    @DisplayName("a JVM that cannot measure allocation reports zero rather than guessing")
    void unsupportedAllocation() {
        allocation.set(new AllocationSampler.Sample(false, 0L, Map.of()));

        assertEquals(0d, assembler().assemble().allocMBps());
    }

    @Test
    @DisplayName("blocked threads are shown first, then the heaviest allocators")
    void ranksBlockedThreadsFirst() {
        TickAssembler assembler = assembler();
        accumulator.recordContention("blocked");
        accumulator.recordPark("parked");
        allocation.set(new AllocationSampler.Sample(true, 0L, Map.of(
                "heavy-allocator", 500 * MB,
                "light-allocator", 1 * MB,
                "blocked", 2 * MB)));

        List<ThreadSlice> slices = assembler.assemble().threadSlices();

        assertEquals("blocked", slices.get(0).name());
        assertEquals("heavy-allocator", slices.get(1).name());
        assertEquals("light-allocator", slices.get(2).name());
        assertEquals("parked", slices.get(3).name());
        assertEquals(ThreadSlice.PARKED, slices.get(3).state());
    }

    @Test
    @DisplayName("the thread lane is capped so the payload stays small")
    void capsThreadSlices() {
        TickAssembler capped = new TickAssembler(accumulator, vitals::get, allocation::get, 3, 2000L, wall::get);
        java.util.Map<String, Long> many = new java.util.HashMap<>();
        for (int index = 0; index < 40; index++) {
            many.put("worker-" + index, (long) index * MB);
        }
        allocation.set(new AllocationSampler.Sample(true, 0L, many));

        assertEquals(3, capped.assemble().threadSlices().size());
    }
}
