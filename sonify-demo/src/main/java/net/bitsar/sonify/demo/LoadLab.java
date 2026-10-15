package net.bitsar.sonify.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Makes the JVM misbehave on purpose.
 *
 * <p>Each method starts background work and returns immediately, so the HTTP request that
 * triggered it does not wait for the damage to finish.
 */
@Component
public class LoadLab implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(LoadLab.class);
    private static final int CHUNK_BYTES = 16 * 1024;
    private static final int LEAK_CHUNK_BYTES = 256 * 1024;

    /** The single monitor every contending thread fights over. */
    private final Object monitor = new Object();

    /** Deliberately retained, so the heap climbs and collections reclaim less and less. */
    private final List<byte[]> retained = new ArrayList<>();

    private final ExecutorService workers;
    private final List<Future<?>> running = new CopyOnWriteArrayList<>();

    public LoadLab() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "load-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.workers = Executors.newCachedThreadPool(factory);
    }

    /** Allocates roughly {@code megabytes} of immediately unreachable garbage, fast. */
    public String allocate(int megabytes) {
        int capped = clamp(megabytes, 1, 4096);
        int chunks = (int) ((long) capped * 1024 * 1024 / CHUNK_BYTES);
        submit(() -> {
            List<byte[]> garbage = new ArrayList<>(1024);
            for (int index = 0; index < chunks && !Thread.currentThread().isInterrupted(); index++) {
                garbage.add(new byte[CHUNK_BYTES]);
                if (garbage.size() >= 1024) {
                    garbage.clear();
                }
            }
        });
        return "allocating " + capped + "MB of garbage in " + chunks + " chunks";
    }

    /** Puts {@code threads} threads into a queue for one monitor, each holding it {@code holdMs}. */
    public String contend(int threads, long holdMs) {
        int cappedThreads = clamp(threads, 1, 64);
        long cappedHold = clamp((int) holdMs, 1, 1000);
        long deadline = System.currentTimeMillis() + 15_000;
        for (int index = 0; index < cappedThreads; index++) {
            submit(() -> {
                while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                    synchronized (monitor) {
                        sleep(cappedHold);
                    }
                }
            });
        }
        return cappedThreads + " threads fighting one monitor, holding it " + cappedHold + "ms each, for 15s";
    }

    /**
     * Retains {@code megabytes} that will never be released until {@link #calm()}.
     *
     * <p>Capped at a third of the heap so the demo leaks visibly without dying.
     */
    public String leak(int megabytes) {
        int capped = clamp(megabytes, 1, 2048);
        long budget = Runtime.getRuntime().maxMemory() / 3;
        int chunks = (int) ((long) capped * 1024 * 1024 / LEAK_CHUNK_BYTES);
        submit(() -> {
            for (int index = 0; index < chunks && !Thread.currentThread().isInterrupted(); index++) {
                synchronized (retained) {
                    if ((long) retained.size() * LEAK_CHUNK_BYTES >= budget) {
                        LOG.warn("Leak budget of {}MB reached; not retaining more", budget / 1048576);
                        return;
                    }
                    retained.add(new byte[LEAK_CHUNK_BYTES]);
                }
                sleep(5);
            }
        });
        return "retaining up to " + capped + "MB (hard cap " + budget / 1048576 + "MB)";
    }

    /** Stops all load and releases everything retained. */
    public String calm() {
        int cancelled = 0;
        for (Future<?> task : running) {
            if (task.cancel(true)) {
                cancelled++;
            }
        }
        running.clear();
        int released;
        synchronized (retained) {
            released = retained.size();
            retained.clear();
        }
        return "cancelled " + cancelled + " running tasks and released "
                + (long) released * LEAK_CHUNK_BYTES / 1048576 + "MB";
    }

    public String status() {
        long retainedBytes;
        synchronized (retained) {
            retainedBytes = (long) retained.size() * LEAK_CHUNK_BYTES;
        }
        running.removeIf(Future::isDone);
        return "running tasks: " + running.size() + ", retained: " + retainedBytes / 1048576 + "MB";
    }

    private void submit(Runnable task) {
        running.removeIf(Future::isDone);
        running.add(workers.submit(task));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
    }
}
