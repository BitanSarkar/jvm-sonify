package net.bitsar.sonify.scenarios;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives load at an application from outside, over real HTTP.
 *
 * <p>This is the only honest way to compare a servlet container with an event loop. Driving a
 * scenario from threads inside the application never touches the server's own thread pool, so it
 * cannot show a Tomcat worker pool filling up or a Netty event loop being held hostage. Here each
 * virtual user is a real connection making real requests, so the server's concurrency model is
 * the thing under test.
 *
 * <pre>
 * java -cp sonify-scenarios/target/classes:... net.bitsar.sonify.scenarios.LoadDriver \
 *      http://localhost:8080/scenario/conc-thread-per-request/once 100 30
 * </pre>
 */
public final class LoadDriver {

    private LoadDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: LoadDriver <url> [users] [seconds]");
            System.exit(2);
        }
        String url = args[0];
        int users = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        AtomicLong ok = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        long[] samples = new long[500_000];
        AtomicInteger index = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(users);

        System.out.printf("driving %s with %d users for %ds%n", url, users, seconds);
        long startedAt = System.currentTimeMillis();

        for (int user = 0; user < users; user++) {
            Thread thread = new Thread(() -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                while (System.currentTimeMillis() < deadline) {
                    long begin = System.nanoTime();
                    try {
                        HttpResponse<Void> response =
                                client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() < 400) {
                            ok.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (Exception failure) {
                        failed.incrementAndGet();
                    }
                    int slot = index.getAndIncrement();
                    if (slot < samples.length) {
                        samples[slot] = (System.nanoTime() - begin) / 1_000_000;
                    }
                }
                done.countDown();
            }, "driver-" + user);
            thread.setDaemon(true);
            thread.start();
        }

        done.await();
        long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
        int recorded = Math.min(index.get(), samples.length);
        long[] sorted = Arrays.copyOf(samples, recorded);
        Arrays.sort(sorted);

        System.out.printf("%n  users       %d%n", users);
        System.out.printf("  completed   %d ok, %d failed%n", ok.get(), failed.get());
        System.out.printf("  throughput  %.1f req/s%n", ok.get() * 1000d / elapsed);
        System.out.printf("  latency     p50 %dms  p95 %dms  p99 %dms  max %dms%n",
                percentile(sorted, 50), percentile(sorted, 95),
                percentile(sorted, 99), recorded == 0 ? 0 : sorted[recorded - 1]);
    }

    private static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int at = (int) Math.ceil(percentile / 100d * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, at))];
    }
}
