package net.bitsar.sonify.demo;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs a fixed, deterministic workload and reports what it cost.
 *
 * <p>Exists so overhead can be measured properly. Driving load from a shell loop measures the
 * cost of spawning curl rather than anything about the JVM, and comparing two separate JVMs
 * compares their JIT and heap states as much as the thing under test. This does identical work
 * on request and reports the process CPU time it consumed, so the same JVM can be measured with
 * a Sonify client connected and disconnected and the difference attributed to Sonify.
 */
@RestController
public class BenchmarkController {

    private static final int CHUNK_BYTES = 16 * 1024;

    private final Object monitor = new Object();

    @GetMapping("/benchmark")
    public String benchmark(@RequestParam(name = "mb", defaultValue = "1500") int megabytes,
                            @RequestParam(name = "threads", defaultValue = "8") int threads,
                            @RequestParam(name = "cycles", defaultValue = "400") int cycles)
            throws InterruptedException {

        long cpuBefore = processCpuNanos();
        long wallBefore = System.nanoTime();

        runWorkload(megabytes, threads, cycles);

        long wallMs = (System.nanoTime() - wallBefore) / 1_000_000;
        long cpuMs = (processCpuNanos() - cpuBefore) / 1_000_000;
        return "{\"wallMs\":" + wallMs + ",\"cpuMs\":" + cpuMs + "}";
    }

    /** Allocation plus real monitor contention, which is what Sonify has to observe. */
    private void runWorkload(int megabytes, int threads, int cycles) throws InterruptedException {
        long chunksPerThread = (long) megabytes * 1024 * 1024 / CHUNK_BYTES / Math.max(1, threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>(threads);

        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    List<byte[]> garbage = new ArrayList<>(512);
                    for (long chunk = 0; chunk < chunksPerThread; chunk++) {
                        garbage.add(new byte[CHUNK_BYTES]);
                        if (garbage.size() >= 512) {
                            garbage.clear();
                        }
                    }
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        synchronized (monitor) {
                            // Held long enough to cross the 10ms contention threshold regularly.
                            Blackhole.spin(250_000);
                        }
                    }
                } finally {
                    done.countDown();
                }
            }, "bench-" + index);
            workers.add(worker);
            worker.start();
        }
        done.await();
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getProcessCpuTime();
        }
        return 0L;
    }

    /** Burns a predictable amount of CPU without being optimised away. */
    static final class Blackhole {

        private static volatile double sink;

        private Blackhole() {
        }

        static void spin(int iterations) {
            double value = 0;
            for (int index = 1; index <= iterations; index++) {
                value += Math.sqrt(index);
            }
            sink = value;
        }
    }
}
