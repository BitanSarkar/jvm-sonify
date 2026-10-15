package net.bitsar.sonify.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllocationRateMeterTest {

    private static final long MB = 1024L * 1024L;
    private static final long WINDOW = 2000L;

    private final AllocationRateMeter meter = new AllocationRateMeter(WINDOW);

    @Test
    @DisplayName("nothing is reported until a full window has elapsed")
    void silentWhileWarmingUp() {
        assertEquals(0d, meter.megabytesPerSecond(0L, 0L));
        assertEquals(0d, meter.megabytesPerSecond(100L, 100 * MB), "a partial window must not be extrapolated");
        assertEquals(0d, meter.megabytesPerSecond(1900L, 200 * MB));
    }

    @Test
    @DisplayName("a steady rate is reported accurately once the window is full")
    void steadyRate() {
        for (long time = 0; time < WINDOW; time += 100L) {
            meter.megabytesPerSecond(time, time / 10 * MB);
        }

        // 100MB per second, sampled across the full window.
        assertEquals(100d, meter.megabytesPerSecond(WINDOW, 200 * MB), 5d);
    }

    @Test
    @DisplayName("a second of samples delivered in one tick is averaged, not extrapolated")
    void batchedSamplesAreNeverExtrapolated() {
        long cumulative = 0;
        double worst = 0;
        // 100MB/s arriving as one 100MB batch per second, which is how JFR actually delivers it.
        for (long time = 0; time <= 10_000L; time += 100L) {
            if (time % 1000 == 0) {
                cumulative += 100 * MB;
            }
            worst = Math.max(worst, meter.megabytesPerSecond(time, cumulative));
        }

        assertTrue(worst < 200d, "a batched 100MB/s must stay near 100MB/s, worst reading was " + worst);
        assertTrue(worst > 50d, "it must still register the load, worst reading was " + worst);
    }

    @Test
    @DisplayName("the rate decays to zero when allocation stops")
    void windowSlidesForward() {
        long cumulative = 0;
        for (long time = 0; time <= 3000L; time += 100L) {
            cumulative += 10 * MB;
            meter.megabytesPerSecond(time, cumulative);
        }
        assertTrue(meter.megabytesPerSecond(3100L, cumulative) > 50d, "should be reading a high rate");

        for (long time = 3200L; time <= 6000L; time += 100L) {
            meter.megabytesPerSecond(time, cumulative);
        }

        assertEquals(0d, meter.megabytesPerSecond(6100L, cumulative), 1e-9);
    }

    @Test
    @DisplayName("an idle application reports zero, never a negative or a NaN")
    void idleIsZero() {
        for (long time = 0; time <= 4000L; time += 100L) {
            assertEquals(0d, meter.megabytesPerSecond(time, 0L));
        }
    }

    @Test
    @DisplayName("a gap longer than the window leaves nothing to divide by and reports zero")
    void gapLongerThanWindowIsSafe() {
        meter.megabytesPerSecond(0L, 0L);

        // Every earlier sample ages out, so this reading has no span to measure against.
        assertEquals(0d, meter.megabytesPerSecond(5000L, 50 * MB));
    }

    @Test
    @DisplayName("bytes spread over a full window give that window's average")
    void averagesAcrossTheWholeWindow() {
        meter.megabytesPerSecond(0L, 0L);

        // 50MB across the 2s window is 25MB/s, and that is what it should say.
        assertEquals(25d, meter.megabytesPerSecond(2000L, 50 * MB), 1e-9);
    }

    @Test
    @DisplayName("a nonsensical window is rejected")
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AllocationRateMeter(0L));
    }
}
