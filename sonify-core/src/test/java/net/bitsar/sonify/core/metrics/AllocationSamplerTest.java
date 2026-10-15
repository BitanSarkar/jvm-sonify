package net.bitsar.sonify.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllocationSamplerTest {

    private static final long MB = 1024L * 1024L;

    /** Thread id to its allocated-bytes counter, as the fake bean will report them. */
    private final Map<Long, Long> live = new LinkedHashMap<>();

    @Test
    @DisplayName("the first sighting of a thread is a baseline, not a spike")
    void firstSightingIsBaselined() {
        live.put(1L, 500 * MB);
        AllocationSampler sampler = sampler();

        AllocationSampler.Sample first = sampler.sample();

        assertEquals(Map.of(), first.bytesByThread(), "a thread's history is not this tick's work");
        assertEquals(500 * MB, first.cumulativeBytes());
    }

    @Test
    @DisplayName("per-thread figures are the exact bytes allocated since the last tick")
    void reportsPerThreadDeltas() {
        live.put(1L, 0L);
        live.put(2L, 0L);
        AllocationSampler sampler = sampler();
        sampler.sample();

        live.put(1L, 10 * MB);
        live.put(2L, 3 * MB);
        AllocationSampler.Sample sample = sampler.sample();

        assertEquals(10 * MB, sample.bytesByThread().get("thread-1"));
        assertEquals(3 * MB, sample.bytesByThread().get("thread-2"));
        assertEquals(13 * MB, sample.cumulativeBytes());
    }

    @Test
    @DisplayName("a thread that did nothing is left out of the lane")
    void idleThreadsAreOmitted() {
        live.put(1L, 0L);
        live.put(2L, 0L);
        AllocationSampler sampler = sampler();
        sampler.sample();

        live.put(1L, 10 * MB);
        AllocationSampler.Sample sample = sampler.sample();

        assertEquals(1, sample.bytesByThread().size());
        assertTrue(sample.bytesByThread().containsKey("thread-1"));
    }

    @Test
    @DisplayName("the running total survives thread death instead of going backwards")
    void totalIsMonotonicAcrossThreadDeath() {
        live.put(1L, 0L);
        live.put(2L, 0L);
        AllocationSampler sampler = sampler();
        sampler.sample();

        live.put(1L, 40 * MB);
        live.put(2L, 60 * MB);
        assertEquals(100 * MB, sampler.sample().cumulativeBytes());

        // Thread 2 exits. Its 60MB must stay in the total, or the rate would read negative.
        live.remove(2L);
        long afterDeath = sampler.sample().cumulativeBytes();
        assertEquals(100 * MB, afterDeath);

        live.put(1L, 50 * MB);
        assertEquals(110 * MB, sampler.sample().cumulativeBytes());
    }

    @Test
    @DisplayName("a thread that dies mid-read is skipped rather than counted as negative")
    void negativeCountersAreSkipped() {
        live.put(1L, 10 * MB);
        live.put(2L, -1L);

        AllocationSampler.Sample sample = sampler().sample();

        assertEquals(10 * MB, sample.cumulativeBytes());
        assertFalse(sample.bytesByThread().containsKey("thread-2"));
    }

    @Test
    @DisplayName("a JVM that cannot report allocation says so instead of reporting zero bytes")
    void unsupportedJvm() {
        AllocationSampler sampler = new AllocationSampler(null);

        AllocationSampler.Sample sample = sampler.sample();

        assertFalse(sampler.supported());
        assertFalse(sample.supported());
        assertEquals(Map.of(), sample.bytesByThread());
    }

    @Test
    @DisplayName("a thread bean that throws degrades instead of breaking the tick")
    void failingBeanDegrades() {
        com.sun.management.ThreadMXBean broken = (com.sun.management.ThreadMXBean) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {com.sun.management.ThreadMXBean.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("no");
                });

        assertFalse(new AllocationSampler(broken).sample().supported());
    }

    @Test
    @DisplayName("the real JVM reports allocation accurately")
    void measuresRealAllocation() {
        AllocationSampler sampler = new AllocationSampler();
        if (!sampler.supported()) {
            return;
        }
        long before = sampler.sample().cumulativeBytes();

        long bytesToAllocate = 64L * MB;
        java.util.List<byte[]> sink = new java.util.ArrayList<>();
        for (int index = 0; index < 64; index++) {
            sink.add(new byte[(int) MB]);
        }
        sink.clear();

        long measured = sampler.sample().cumulativeBytes() - before;

        assertTrue(measured >= bytesToAllocate,
                "expected at least " + bytesToAllocate + " bytes, measured " + measured);
        assertTrue(measured < bytesToAllocate * 3,
                "expected roughly " + bytesToAllocate + " bytes, measured " + measured);
    }

    private AllocationSampler sampler() {
        return new AllocationSampler(fakeBean());
    }

    /** Only the three counter methods are used; getThreadInfo returning null yields "thread-N". */
    private com.sun.management.ThreadMXBean fakeBean() {
        return (com.sun.management.ThreadMXBean) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {com.sun.management.ThreadMXBean.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllThreadIds" -> live.keySet().stream().mapToLong(Long::longValue).toArray();
                    case "getThreadAllocatedBytes" -> {
                        long[] ids = (long[]) args[0];
                        long[] bytes = new long[ids.length];
                        for (int index = 0; index < ids.length; index++) {
                            bytes[index] = live.getOrDefault(ids[index], -1L);
                        }
                        yield bytes;
                    }
                    case "getThreadInfo" -> null;
                    case "isThreadAllocatedMemorySupported", "isThreadAllocatedMemoryEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
