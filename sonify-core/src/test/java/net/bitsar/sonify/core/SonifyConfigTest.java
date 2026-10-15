package net.bitsar.sonify.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SonifyConfigTest {

    @Test
    @DisplayName("defaults match the documented property defaults")
    void defaults() {
        SonifyConfig config = SonifyConfig.defaults();

        assertEquals(Duration.ofMillis(100), config.tick());
        assertEquals(Duration.ofMillis(10), config.contentionThreshold());
        assertEquals(Duration.ofMillis(20), config.parkThreshold());
        assertEquals(Duration.ofMillis(5), config.ioWaitThreshold());
        assertEquals(Duration.ofSeconds(2), config.allocationWindow());
        assertEquals(512, config.bufferCapacity());
        assertEquals(8, config.maxClients());
        assertEquals(16, config.maxThreadSlices());
        assertEquals(Duration.ofSeconds(30), config.gracePeriod());
        assertFalse(config.executionSampleEnabled());
    }

    @Test
    @DisplayName("nonsense tuning is rejected at construction")
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> SonifyConfig.builder().tick(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> SonifyConfig.builder().tick(null).build());
        assertThrows(IllegalArgumentException.class, () -> SonifyConfig.builder().bufferCapacity(0).build());
        assertThrows(IllegalArgumentException.class, () -> SonifyConfig.builder().maxClients(0).build());
        assertThrows(IllegalArgumentException.class, () -> SonifyConfig.builder().maxThreadSlices(0).build());
        assertThrows(IllegalArgumentException.class, () -> SonifyConfig.builder().allocationWindow(Duration.ZERO).build());
    }
}
