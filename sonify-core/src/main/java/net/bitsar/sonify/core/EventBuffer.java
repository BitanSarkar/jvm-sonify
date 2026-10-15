package net.bitsar.sonify.core;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.bitsar.sonify.core.event.SonifyEvent;

/**
 * A bounded queue of pending events for one client that drops the oldest entry when full.
 *
 * <p>{@link #offer(SonifyEvent)} is called from the JFR stream thread and the tick thread, so it
 * never blocks and never waits on a slow browser: a client that cannot keep up loses the oldest
 * events rather than stalling the JVM being measured.
 */
public final class EventBuffer {

    private static final int DROP_ATTEMPTS = 8;

    private final ArrayBlockingQueue<SonifyEvent> queue;
    private final AtomicLong dropped = new AtomicLong();

    public EventBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Adds an event, discarding older ones if necessary. Never blocks. */
    public void offer(SonifyEvent event) {
        if (queue.offer(event)) {
            return;
        }
        for (int attempt = 0; attempt < DROP_ATTEMPTS; attempt++) {
            if (queue.poll() != null) {
                dropped.incrementAndGet();
            }
            if (queue.offer(event)) {
                return;
            }
        }
        dropped.incrementAndGet();
    }

    /** Waits up to {@code timeout} for the next event, or returns null if none arrives. */
    public SonifyEvent poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /** Total events discarded because this client was too slow. */
    public long dropped() {
        return dropped.get();
    }
}
