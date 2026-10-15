package net.bitsar.sonify.core;

import java.time.Duration;

/**
 * Immutable tuning for a {@link SonifyBroker}.
 *
 * <p>Core deliberately owns no binding framework, so the Spring auto-configuration maps its
 * properties onto this record via {@link #builder()}.
 */
public record SonifyConfig(Duration tick,
                           Duration contentionThreshold,
                           Duration parkThreshold,
                           Duration ioWaitThreshold,
                           Duration allocationWindow,
                           int bufferCapacity,
                           int maxClients,
                           int maxThreadSlices,
                           Duration gracePeriod,
                           boolean executionSampleEnabled,
                           Duration executionSamplePeriod) {

    public SonifyConfig {
        if (tick == null || tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("tick must be positive");
        }
        if (bufferCapacity < 1) {
            throw new IllegalArgumentException("bufferCapacity must be at least 1");
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("maxClients must be at least 1");
        }
        if (allocationWindow == null || allocationWindow.isZero() || allocationWindow.isNegative()) {
            throw new IllegalArgumentException("allocationWindow must be positive");
        }
        if (maxThreadSlices < 1) {
            throw new IllegalArgumentException("maxThreadSlices must be at least 1");
        }
    }

    public static SonifyConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link SonifyConfig}. */
    public static final class Builder {

        private Duration tick = Duration.ofMillis(100);
        private Duration contentionThreshold = Duration.ofMillis(10);
        private Duration parkThreshold = Duration.ofMillis(20);
        private Duration ioWaitThreshold = Duration.ofMillis(5);
        private Duration allocationWindow = Duration.ofSeconds(2);
        private int bufferCapacity = 512;
        private int maxClients = 8;
        private int maxThreadSlices = 16;
        private Duration gracePeriod = Duration.ofSeconds(30);
        private boolean executionSampleEnabled;
        private Duration executionSamplePeriod = Duration.ofMillis(100);

        private Builder() {
        }

        public Builder tick(Duration value) {
            this.tick = value;
            return this;
        }

        public Builder contentionThreshold(Duration value) {
            this.contentionThreshold = value;
            return this;
        }

        public Builder parkThreshold(Duration value) {
            this.parkThreshold = value;
            return this;
        }

        public Builder ioWaitThreshold(Duration value) {
            this.ioWaitThreshold = value;
            return this;
        }

        public Builder allocationWindow(Duration value) {
            this.allocationWindow = value;
            return this;
        }

        public Builder bufferCapacity(int value) {
            this.bufferCapacity = value;
            return this;
        }

        public Builder maxClients(int value) {
            this.maxClients = value;
            return this;
        }

        public Builder maxThreadSlices(int value) {
            this.maxThreadSlices = value;
            return this;
        }

        public Builder gracePeriod(Duration value) {
            this.gracePeriod = value;
            return this;
        }

        public Builder executionSampleEnabled(boolean value) {
            this.executionSampleEnabled = value;
            return this;
        }

        public Builder executionSamplePeriod(Duration value) {
            this.executionSamplePeriod = value;
            return this;
        }

        public SonifyConfig build() {
            return new SonifyConfig(tick, contentionThreshold, parkThreshold, ioWaitThreshold, allocationWindow, bufferCapacity, maxClients, maxThreadSlices, gracePeriod,
                    executionSampleEnabled, executionSamplePeriod);
        }
    }
}
