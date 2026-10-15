package net.bitsar.sonify.scenarios;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one scenario with a fixed number of concurrent users for a fixed time.
 *
 * <p>Each user is a real platform thread issuing requests back to back, which is what a
 * thread-per-request server actually experiences. Latency percentiles are recorded because they
 * are often the only thing that moves: a pool that has run dry shows nothing in heap or CPU, and
 * announces itself purely as a p99 that has gone through the roof.
 */
public final class ScenarioRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioRunner.class);
    private static final int MAX_SAMPLES = 200_000;

    /**
     * Runs {@code oneRequest} from {@code users} threads until the duration elapses.
     *
     * @param oneRequest a single user request; throwing counts as a failure and keeps going,
     *                   because an upstream that fails is the point of several scenarios
     */
    public ScenarioOutcome run(Scenario scenario, ScenarioRequest request, Consumer<Integer> oneRequest) {
        long deadline = System.currentTimeMillis() + request.duration().toMillis();
        AtomicLong completed = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        long[] samples = new long[MAX_SAMPLES];
        AtomicInteger sampleIndex = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(request.users());
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(request.users());

        for (int user = 0; user < request.users(); user++) {
            int userId = user;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                        long started = System.nanoTime();
                        try {
                            oneRequest.accept(userId);
                            completed.incrementAndGet();
                        } catch (Throwable failure) {
                            failed.incrementAndGet();
                        }
                        long tookMs = (System.nanoTime() - started) / 1_000_000;
                        int slot = sampleIndex.getAndIncrement();
                        if (slot < MAX_SAMPLES) {
                            samples[slot] = tookMs;
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "user-" + user);
            thread.setDaemon(true);
            thread.start();
        }

        long startedAt;
        try {
            ready.await();
            startedAt = System.currentTimeMillis();
            go.countDown();
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            startedAt = System.currentTimeMillis();
        }

        long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
        int recorded = Math.min(sampleIndex.get(), MAX_SAMPLES);
        long[] sorted = Arrays.copyOf(samples, recorded);
        Arrays.sort(sorted);

        ScenarioOutcome outcome = new ScenarioOutcome(scenario.id(),
                request.users(),
                elapsed,
                completed.get(),
                failed.get(),
                completed.get() * 1000d / elapsed,
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                recorded == 0 ? 0 : sorted[recorded - 1],
                note(scenario, completed.get(), failed.get(), percentile(sorted, 99)));
        LOG.info("scenario {} with {} users: {} ok, {} failed, {}/s, p99 {}ms",
                scenario.id(), request.users(), outcome.completed(), outcome.failed(),
                Math.round(outcome.throughputPerSecond()), outcome.p99Ms());
        return outcome;
    }

    private static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100d * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String note(Scenario scenario, long completed, long failed, long p99) {
        if (completed == 0) {
            return "nothing completed; the scenario was fully stalled";
        }
        if (failed > completed / 10) {
            return "a tenth or more of requests failed outright";
        }
        if (p99 > 2000) {
            return "p99 above two seconds: queueing, not working";
        }
        return "completed cleanly";
    }
}
