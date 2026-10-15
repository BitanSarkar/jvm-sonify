package net.bitsar.sonify.core;

import java.time.Duration;
import net.bitsar.sonify.core.event.SonifyEvent;

/**
 * One connected client's view of the stream.
 *
 * <p>Close it, or let the transport close it, to release the slot: the broker stops the recording
 * stream once the last subscription has gone and the grace period has elapsed.
 */
public final class Subscription implements AutoCloseable {

    private final SonifyBroker broker;
    private final EventBuffer buffer;

    Subscription(SonifyBroker broker, int bufferCapacity) {
        this.broker = broker;
        this.buffer = new EventBuffer(bufferCapacity);
    }

    /** Waits up to {@code timeout} for the next event, or returns null if none arrives. */
    public SonifyEvent poll(Duration timeout) throws InterruptedException {
        return buffer.poll(timeout);
    }

    /** Events discarded because this client could not keep up. */
    public long dropped() {
        return buffer.dropped();
    }

    public int pending() {
        return buffer.size();
    }

    @Override
    public void close() {
        broker.unsubscribe(this);
    }

    void deliver(SonifyEvent event) {
        buffer.offer(event);
    }
}
