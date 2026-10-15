package net.bitsar.sonify.core.dev;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.bitsar.sonify.core.SonifyBroker;
import net.bitsar.sonify.core.SonifyConfig;
import net.bitsar.sonify.core.Subscription;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.json.SonifyEventJson;

/**
 * Runs sonify-core on its own, with no Spring and no browser, and prints the JSON that would go
 * down the wire. It drives itself through the same story the demo app tells, so the core module
 * can be verified end to end before any web layer exists.
 *
 * <pre>
 * mvn -pl sonify-core test-compile exec:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.bitsar.sonify.core.dev.DevMain
 * </pre>
 */
public final class DevMain {

    private static final Object MONITOR = new Object();
    private static final List<byte[]> RETAINED = new ArrayList<>();

    private DevMain() {
    }

    public static void main(String[] args) throws Exception {
        SonifyConfig config = SonifyConfig.builder()
                .tick(Duration.ofMillis(100))
                .contentionThreshold(Duration.ofMillis(10))
                .build();

        SonifyBroker broker = new SonifyBroker(config);
        System.out.println("# before any subscriber: running=" + broker.status().running());

        try (Subscription subscription = broker.subscribe()) {
            System.out.println("# subscribed: " + broker.status());
            Thread printer = startPrinter(subscription);
            runStory();
            printer.interrupt();
            printer.join(Duration.ofSeconds(2).toMillis());
            System.out.println("# final: " + broker.status());
        } finally {
            broker.close();
        }
        System.out.println("# after close: running=" + broker.status().running());
    }

    private static Thread startPrinter(Subscription subscription) {
        AtomicInteger ticks = new AtomicInteger();
        Thread printer = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    SonifyEvent event = subscription.poll(Duration.ofMillis(250));
                    if (event == null) {
                        continue;
                    }
                    // Ticks are frequent and dull on stdout, so only every fifth one is printed.
                    if (!"tick".equals(event.type()) || ticks.incrementAndGet() % 5 == 0) {
                        System.out.println(SonifyEventJson.toJson(event));
                    }
                }
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        }, "dev-printer");
        printer.setDaemon(true);
        printer.start();
        return printer;
    }

    private static void runStory() throws Exception {
        phase("calm: nothing much happening");
        Thread.sleep(2_000);

        phase("allocation spike: GC pressure, expect gc events");
        allocate(Duration.ofSeconds(5));

        phase("contention storm: 12 threads fighting one monitor");
        contend(Duration.ofSeconds(5), 12);

        phase("leak: retaining memory so the heap climbs and reclaims little");
        leak(Duration.ofSeconds(4));

        phase("recovery: releasing everything");
        RETAINED.clear();
        System.gc();
        Thread.sleep(3_000);
    }

    private static void phase(String description) {
        System.out.println("# ---- " + description);
    }

    private static void allocate(Duration duration) throws InterruptedException {
        runWorkers(4, duration, () -> {
            List<byte[]> garbage = new ArrayList<>(512);
            for (int index = 0; index < 512; index++) {
                garbage.add(new byte[16 * 1024]);
            }
            garbage.clear();
        });
    }

    private static void contend(Duration duration, int threads) throws InterruptedException {
        runWorkers(threads, duration, () -> {
            synchronized (MONITOR) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private static void leak(Duration duration) throws InterruptedException {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        // Capped so the demo leaks visibly without exhausting the heap and killing the run.
        long maxRetainedBytes = Runtime.getRuntime().maxMemory() / 3;
        long retainedBytes = 0;
        while (System.currentTimeMillis() < deadline && retainedBytes < maxRetainedBytes) {
            synchronized (RETAINED) {
                for (int index = 0; index < 16; index++) {
                    RETAINED.add(new byte[256 * 1024]);
                    retainedBytes += 256 * 1024;
                }
            }
            Thread.sleep(100);
        }
    }

    private static void runWorkers(int count, Duration duration, Runnable unitOfWork) throws InterruptedException {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        List<Thread> workers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Thread worker = new Thread(() -> {
                while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                    unitOfWork.run();
                }
            }, "dev-load-" + index);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }
}
