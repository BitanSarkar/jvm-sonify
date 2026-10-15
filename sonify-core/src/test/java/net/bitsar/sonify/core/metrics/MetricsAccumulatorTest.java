package net.bitsar.sonify.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsAccumulatorTest {

    private final MetricsAccumulator accumulator = new MetricsAccumulator();

    @Test
    @DisplayName("counts are of distinct threads, not of events")
    void countsDistinctThreads() {
        accumulator.recordContention("worker-1");
        accumulator.recordContention("worker-1");
        accumulator.recordContention("worker-2");
        accumulator.recordPark("idler");

        MetricsAccumulator.Snapshot window = accumulator.drain();

        assertEquals(2, window.contendedThreads());
        assertEquals(1, window.parkedThreads());
    }

    @Test
    @DisplayName("each thread's own blocking is reported for the per-thread lane")
    void reportsPerThreadCounts() {
        accumulator.recordContention("worker-1");
        accumulator.recordContention("worker-1");
        accumulator.recordPark("worker-1");

        Map<String, MetricsAccumulator.Counts> byThread = accumulator.drain().byThread();

        assertEquals(new MetricsAccumulator.Counts(2, 1), byThread.get("worker-1"));
    }

    @Test
    @DisplayName("draining starts a fresh window")
    void drainResetsTheWindow() {
        accumulator.recordContention("worker");
        accumulator.drain();

        MetricsAccumulator.Snapshot second = accumulator.drain();

        assertEquals(0, second.contendedThreads());
        assertEquals(0, second.parkedThreads());
        assertEquals(Map.of(), second.byThread());
    }

    @Test
    @DisplayName("a thread that both blocked and parked counts as contended")
    void contentionOutranksParking() {
        accumulator.recordContention("worker");
        accumulator.recordPark("worker");

        MetricsAccumulator.Snapshot window = accumulator.drain();

        assertEquals(1, window.contendedThreads());
        assertEquals(1, window.parkedThreads());
        assertEquals(1, window.byThread().size());
    }

    @Test
    @DisplayName("thread churn cannot grow the tracking map without bound")
    void trackedThreadsAreBounded() {
        for (int index = 0; index < 5000; index++) {
            accumulator.recordContention("short-lived-" + index);
        }

        assertTrue(accumulator.drain().byThread().size() <= 256,
                "expected the tracked-thread cap to hold");
    }

    @Test
    @DisplayName("a missing thread name is still accounted for")
    void nullThreadNameIsTolerated() {
        accumulator.recordContention(null);

        assertTrue(accumulator.drain().byThread().containsKey("unknown"));
    }
}
