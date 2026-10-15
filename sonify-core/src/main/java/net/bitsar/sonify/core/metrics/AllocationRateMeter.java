package net.bitsar.sonify.core.metrics;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Turns a monotonic allocated-bytes counter into a megabytes-per-second rate.
 *
 * <p>Flight Recorder delivers allocation samples in batches of roughly a second, so a rate
 * measured over a single 100ms tick is meaningless: one tick receives a whole second of samples
 * and would be extrapolated to ten times the real rate, while the ticks around it report zero.
 * Averaging the counter over a window that spans several batches removes that artefact.
 *
 * <p>Two consequences are worth knowing. Nothing is reported until a full window has elapsed,
 * because a partial window is exactly the short span that produces an absurd figure. And once
 * running, the figure is a trailing average, so a window holding three batches instead of two can
 * read high by up to half for a moment; the error is bounded and self-correcting, which a raw
 * per-tick rate is not.
 *
 * <p>Not thread safe: one instance belongs to one tick thread.
 */
public final class AllocationRateMeter {

    private static final double BYTES_PER_MB = 1024d * 1024d;
    private static final long NOT_STARTED = Long.MIN_VALUE;

    private final long windowMillis;
    private final Deque<long[]> samples = new ArrayDeque<>();

    private long startedAt = NOT_STARTED;

    public AllocationRateMeter(long windowMillis) {
        if (windowMillis < 1) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.windowMillis = windowMillis;
    }

    /**
     * Records the counter's current value and returns the rate across the trailing window.
     *
     * @param nowMillis       the time of this reading
     * @param cumulativeBytes total bytes allocated since the pipeline started, never decreasing
     * @return megabytes per second, or zero while the first window is still filling
     */
    public double megabytesPerSecond(long nowMillis, long cumulativeBytes) {
        if (startedAt == NOT_STARTED) {
            startedAt = nowMillis;
        }
        samples.addLast(new long[] {nowMillis, cumulativeBytes});
        while (samples.size() > 1 && nowMillis - samples.peekFirst()[0] > windowMillis) {
            samples.removeFirst();
        }
        if (nowMillis - startedAt < windowMillis) {
            return 0d;
        }

        long[] oldest = samples.peekFirst();
        long elapsed = nowMillis - oldest[0];
        long bytes = cumulativeBytes - oldest[1];
        if (elapsed <= 0 || bytes <= 0) {
            return 0d;
        }
        return (bytes / BYTES_PER_MB) / (elapsed / 1000d);
    }
}
