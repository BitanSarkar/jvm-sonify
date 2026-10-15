package net.bitsar.sonify.autoconfigure;

import java.time.Duration;
import net.bitsar.sonify.core.SonifyConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for JVM Sonify, under the {@code sonify} prefix.
 *
 * <p>Disabled by default: an application that merely has the starter on its classpath registers
 * no beans, exposes no endpoint and measures nothing.
 */
@ConfigurationProperties("sonify")
public class SonifyProperties {

    /** Whether to expose the Sonify stream and UI. Off unless explicitly enabled. */
    private boolean enabled;

    /** Base path for the UI and the SSE stream. */
    private String basePath = "/sonify";

    /** How often a snapshot of every vital sign is published. */
    private Duration tick = Duration.ofMillis(100);

    /** Maximum number of simultaneous browsers; further connections are refused with 503. */
    private int maxClients = 8;

    /** Pending events held per client before the oldest are dropped. */
    private int bufferCapacity = 512;

    /** Threads shown in the per-thread lane, most interesting first. */
    private int maxThreadSlices = 16;

    /** How long the pipeline keeps running after the last client disconnects. */
    private Duration gracePeriod = Duration.ofSeconds(30);

    private final Events events = new Events();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public Duration getTick() {
        return tick;
    }

    public void setTick(Duration tick) {
        this.tick = tick;
    }

    public int getMaxClients() {
        return maxClients;
    }

    public void setMaxClients(int maxClients) {
        this.maxClients = maxClients;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }

    public void setBufferCapacity(int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
    }

    public int getMaxThreadSlices() {
        return maxThreadSlices;
    }

    public void setMaxThreadSlices(int maxThreadSlices) {
        this.maxThreadSlices = maxThreadSlices;
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    public Events getEvents() {
        return events;
    }

    /** Maps these properties onto the Spring-free core configuration. */
    public SonifyConfig toConfig() {
        return SonifyConfig.builder()
                .tick(tick)
                .contentionThreshold(events.getContentionThreshold())
                .parkThreshold(events.getParkThreshold())
                .ioWaitThreshold(events.getIoWaitThreshold())
                .allocationWindow(events.getAllocationWindow())
                .bufferCapacity(bufferCapacity)
                .maxClients(maxClients)
                .maxThreadSlices(maxThreadSlices)
                .gracePeriod(gracePeriod)
                .build();
    }

    /** Thresholds applied to the Flight Recorder subscriptions. */
    public static class Events {

        /** Only report monitor contention that blocked a thread for at least this long. */
        private Duration contentionThreshold = Duration.ofMillis(10);

        /**
         * Only report parking longer than this. Kept above the contention threshold because most
         * parking is simply an idle pool thread waiting for work, not a symptom of anything.
         */
        private Duration parkThreshold = Duration.ofMillis(20);

        /**
         * Only report socket reads that blocked a thread for at least this long.
         *
         * <p>Lower than the other thresholds on purpose. A query against a nearby database
         * returns in well under a millisecond, so a 20ms floor reports nothing at all; what
         * makes an N+1 slow is the number of reads, not the length of any one of them. Five
         * milliseconds catches a genuinely slow read while ignoring the ordinary fast ones.
         *
         * <p>Note that this only sees blocking socket I/O, which is what JDBC drivers and
         * classic HTTP clients use. A non-blocking client such as java.net.http parks its
         * calling thread on a future instead, so its waiting shows up in the parked count.
         */
        private Duration ioWaitThreshold = Duration.ofMillis(5);

        /**
         * Window over which the allocation rate is averaged. Flight Recorder and the allocation
         * counters both move in steps, so a window of a second or more gives a stable figure.
         */
        private Duration allocationWindow = Duration.ofSeconds(2);

        public Duration getContentionThreshold() {
            return contentionThreshold;
        }

        public void setContentionThreshold(Duration contentionThreshold) {
            this.contentionThreshold = contentionThreshold;
        }

        public Duration getParkThreshold() {
            return parkThreshold;
        }

        public void setParkThreshold(Duration parkThreshold) {
            this.parkThreshold = parkThreshold;
        }

        public Duration getIoWaitThreshold() {
            return ioWaitThreshold;
        }

        public void setIoWaitThreshold(Duration ioWaitThreshold) {
            this.ioWaitThreshold = ioWaitThreshold;
        }

        public Duration getAllocationWindow() {
            return allocationWindow;
        }

        public void setAllocationWindow(Duration allocationWindow) {
            this.allocationWindow = allocationWindow;
        }
    }
}
