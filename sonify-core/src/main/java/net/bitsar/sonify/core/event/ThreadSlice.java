package net.bitsar.sonify.core.event;

/**
 * What one thread was seen doing during a single tick.
 *
 * <p>{@code allocBytes} is an exact per-thread figure from the platform thread bean, while
 * {@code blocks} and {@code parks} are counts of Flight Recorder events that crossed their
 * configured thresholds.
 */
public record ThreadSlice(String name, String state, long allocBytes, int blocks, int parks) {

    public static final String RUNNING = "running";
    public static final String CONTENDED = "contended";
    public static final String PARKED = "parked";
}
