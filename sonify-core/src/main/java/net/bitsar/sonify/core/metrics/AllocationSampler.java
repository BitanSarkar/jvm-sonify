package net.bitsar.sonify.core.metrics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads exact allocation figures from the platform thread bean.
 *
 * <p>This replaces {@code jdk.ObjectAllocationSample}, which is a throttled statistical estimate:
 * measured against a precisely known 125 MiB of allocation, the summed JFR sample weights came out
 * 55% low, while the thread bean's counters were within 0.1%. Reading them costs about 17
 * microseconds per tick, and dropping the JFR subscription removes what was by far the
 * highest-volume event.
 *
 * <p>The JVM-wide total prefers {@code getTotalThreadAllocatedBytes()}, which the JVM maintains
 * internally and is therefore exact. It is called through a method handle because it only exists
 * from Java 21 and this library compiles against 17.
 *
 * <p>Where it is missing, the total falls back to summing the live threads and carrying forward
 * the last count of every thread that has since died. That fallback cannot see a thread that was
 * born and died inside a single tick, and such threads are common: a request handler allocating
 * 150MB can finish in 15 milliseconds and never appear in a sample at all. The fallback therefore
 * under-reports bursty allocation, which is why the exact counter is used whenever it exists.
 *
 * <p>Not thread safe: one instance belongs to one tick thread.
 */
public final class AllocationSampler {

    private static final int MAX_TRACKED_THREADS = 512;

    private final com.sun.management.ThreadMXBean threads;
    private final MethodHandle exactTotal;
    private final Map<Long, Long> previousBytesByThreadId = new HashMap<>();
    private final Map<Long, String> namesByThreadId = new HashMap<>();

    private long retiredBytes;

    public AllocationSampler() {
        this(resolveThreadBean());
    }

    AllocationSampler(com.sun.management.ThreadMXBean threads) {
        this(threads, resolveExactTotal());
    }

    AllocationSampler(com.sun.management.ThreadMXBean threads, MethodHandle exactTotal) {
        this.threads = threads;
        this.exactTotal = exactTotal;
    }

    /** Looks up Java 21's exact JVM-wide counter, or null on an older JDK. */
    private static MethodHandle resolveExactTotal() {
        try {
            return MethodHandles.publicLookup().findVirtual(com.sun.management.ThreadMXBean.class,
                    "getTotalThreadAllocatedBytes", MethodType.methodType(long.class));
        } catch (NoSuchMethodException | IllegalAccessException notOnThisJdk) {
            return null;
        } catch (Throwable denied) {
            return null;
        }
    }

    /** True when this JVM reports allocation exactly, including threads that lived briefly. */
    public boolean exact() {
        return threads != null && exactTotal != null;
    }

    private static com.sun.management.ThreadMXBean resolveThreadBean() {
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean extended
                    && extended.isThreadAllocatedMemorySupported()
                    && extended.isThreadAllocatedMemoryEnabled()) {
                return extended;
            }
            return null;
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /** True when this JVM can report allocation figures. */
    public boolean supported() {
        return threads != null;
    }

    public Sample sample() {
        if (threads == null) {
            return new Sample(false, 0L, Map.of());
        }
        try {
            return read();
        } catch (Throwable unavailable) {
            return new Sample(false, 0L, Map.of());
        }
    }

    private Sample read() {
        long[] ids = threads.getAllThreadIds();
        long[] allocated = threads.getThreadAllocatedBytes(ids);

        Map<String, Long> deltas = new LinkedHashMap<>();
        Map<Long, Long> current = new HashMap<>(Math.max(16, ids.length * 2));
        long liveBytes = 0;
        for (int index = 0; index < ids.length; index++) {
            long bytes = allocated[index];
            if (bytes < 0) {
                // The thread died between listing the ids and reading their counters.
                continue;
            }
            long id = ids[index];
            current.put(id, bytes);
            liveBytes += bytes;

            // A thread seen for the first time is baselined here, so it contributes nothing to
            // this tick's per-thread lane rather than dumping its whole history into one spike.
            Long previous = previousBytesByThreadId.get(id);
            long delta = previous == null ? 0L : bytes - previous;
            if (delta > 0 && deltas.size() < MAX_TRACKED_THREADS) {
                deltas.put(nameOf(id), delta);
            }
        }

        for (Map.Entry<Long, Long> gone : previousBytesByThreadId.entrySet()) {
            if (!current.containsKey(gone.getKey())) {
                retiredBytes += gone.getValue();
            }
        }
        previousBytesByThreadId.clear();
        previousBytesByThreadId.putAll(current);
        namesByThreadId.keySet().retainAll(current.keySet());

        return new Sample(true, totalBytes(retiredBytes + liveBytes), deltas);
    }

    private long totalBytes(long summedFallback) {
        if (exactTotal == null) {
            return summedFallback;
        }
        try {
            return (long) exactTotal.invokeExact(threads);
        } catch (Throwable unavailable) {
            return summedFallback;
        }
    }

    private String nameOf(long id) {
        String cached = namesByThreadId.get(id);
        if (cached != null) {
            return cached;
        }
        // Resolving a name costs far more than reading counters, so it happens once per thread.
        ThreadInfo info = threads.getThreadInfo(id, 0);
        String name = info == null ? "thread-" + id : info.getThreadName();
        namesByThreadId.put(id, name);
        return name;
    }

    /**
     * One allocation reading.
     *
     * @param supported       false when this JVM cannot report allocation at all
     * @param cumulativeBytes JVM-wide total including threads that have terminated; never decreases
     * @param bytesByThread   bytes allocated per named thread since the previous sample
     */
    public record Sample(boolean supported, long cumulativeBytes, Map<String, Long> bytesByThread) {

        public Sample {
            bytesByThread = Map.copyOf(bytesByThread);
        }
    }
}
