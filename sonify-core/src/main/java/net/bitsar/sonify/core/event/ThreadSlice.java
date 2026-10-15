package net.bitsar.sonify.core.event;

/**
 * What one thread was seen doing during a single tick.
 *
 * <p>{@code allocBytes} is an exact per-thread figure from the platform thread bean; the rest are
 * counts of Flight Recorder events that crossed their configured thresholds.
 */
public record ThreadSlice(String name,
                          String state,
                          long allocBytes,
                          int blocks,
                          int parks,
                          int ioWaits) {

    public static final String RUNNING = "running";
    public static final String CONTENDED = "contended";
    /** Blocked in a socket read: waiting on a database, a cache or a third-party API. */
    public static final String WAITING = "waiting";
    public static final String PARKED = "parked";
}
