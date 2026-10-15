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

    /** What the fake bean's exact JVM-wide counter should return. */
    private final java.util.concurrent.atomic.AtomicLong exactTotal =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    private java.lang.invoke.MethodHandle exactTotalHandle() throws Throwable {
        return java.lang.invoke.MethodHandles.publicLookup().findVirtual(
                com.sun.management.ThreadMXBean.class, "getTotalThreadAllocatedBytes",
                java.lang.invoke.MethodType.methodType(long.class));
    }

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
    @DisplayName("the exact JVM-wide counter is preferred over summing live threads")
    void prefersExactTotal() throws Throwable {
        live.put(1L, 10 * MB);
        exactTotal.set(999 * MB);

        AllocationSampler sampler = new AllocationSampler(fakeBean(), exactTotalHandle());

        assertTrue(sampler.exact());
        assertEquals(999 * MB, sampler.sample().cumulativeBytes(),
                "summing live threads would have said 10MB and missed every short-lived thread");
    }

    @Test
    @DisplayName("an exact counter that fails falls back to summing rather than reporting nothing")
    void fallsBackWhenExactCounterFails() throws Throwable {
        live.put(1L, 10 * MB);
        exactTotal.set(-1L);

        AllocationSampler sampler = new AllocationSampler(fakeBean(), exactTotalHandle());

        assertEquals(10 * MB, sampler.sample().cumulativeBytes());
    }

    @Test
    @DisplayName("an older JDK without the exact counter still reports a total")
    void olderJdkUsesFallback() {
        live.put(1L, 10 * MB);

        AllocationSampler sampler = new AllocationSampler(fakeBean(), null);

        assertFalse(sampler.exact());
        assertEquals(10 * MB, sampler.sample().cumulativeBytes());
    }

    @Test
    @DisplayName("a thread that lives and dies inside one tick is still counted when exact")
    void shortLivedThreadIsCountedWhenExact() throws Exception {
        AllocationSampler sampler = new AllocationSampler();
        if (!sampler.exact()) {
            return;
        }
        long before = sampler.sample().cumulativeBytes();

        // Allocates 64MB and exits well within one tick, so no sample ever observes it alive.
        Thread burst = new Thread(() -> {
            java.util.List<byte[]> sink = new java.util.ArrayList<>();
            for (int index = 0; index < 64; index++) {
                sink.add(new byte[(int) MB]);
            }
            sink.clear();
        }, "burst");
        burst.start();
        burst.join();

        long measured = sampler.sample().cumulativeBytes() - before;

        assertTrue(measured >= 64 * MB,
                "a thread that died between samples must still be counted, measured " + measured);
    }

    @Test
    @DisplayName("a JVM that cannot report allocation says so instead of reporting zero bytes")
    void unsupportedJvm() {
        AllocationSampler sampler = new AllocationSampler(null, null);

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

        assertFalse(new AllocationSampler(broken, null).sample().supported());
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
        // null handle: exercise the summing fallback explicitly, whatever JDK the tests run on.
        return new AllocationSampler(fakeBean(), null);
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
                    case "getTotalThreadAllocatedBytes" -> {
                        long total = exactTotal.get();
                        if (total < 0) {
                            throw new UnsupportedOperationException("no exact counter");
                        }
                        yield total;
                    }
                    case "isThreadAllocatedMemorySupported", "isThreadAllocatedMemoryEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
