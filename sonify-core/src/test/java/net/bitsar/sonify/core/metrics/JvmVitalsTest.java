package net.bitsar.sonify.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JvmVitalsTest {

    private final AtomicReference<Double> reading = new AtomicReference<>(-1d);
    private final AtomicLong nanos = new AtomicLong();

    @Test
    @DisplayName("heap and thread readings are available on every JVM")
    void readsHeapAndThreads() {
        JvmVitals.Sample sample = new JvmVitals().sample();

        assertTrue(sample.heapUsed() > 0, "heapUsed should be positive");
        assertTrue(sample.heapCommitted() >= sample.heapUsed(), "committed should cover used");
        assertTrue(sample.threadCount() > 0, "threadCount should be positive");
    }

    @Test
    @DisplayName("CPU load is either a ratio in 0..1 or null, never a bogus number")
    void cpuIsRatioOrNull() {
        JvmVitals.Sample sample = new JvmVitals().sample();

        for (Double value : new Double[] {sample.processCpu(), sample.machineCpu()}) {
            if (value != null) {
                assertTrue(value >= 0d && value <= 1d, "expected 0..1 but got " + value);
            }
        }
    }

    @Test
    @DisplayName("sampling repeatedly is stable")
    void samplingIsRepeatable() {
        JvmVitals vitals = new JvmVitals();
        for (int index = 0; index < 1000; index++) {
            assertNotNull(vitals.sample());
        }
    }

    @Test
    @DisplayName("a JVM without the extended OS bean still reports heap and threads")
    void degradesWithoutExtendedOsBean() {
        JvmVitals degraded = new JvmVitals(ManagementFactory.getMemoryMXBean(),
                ManagementFactory.getThreadMXBean(), null);

        JvmVitals.Sample sample = degraded.sample();

        assertNull(sample.processCpu());
        assertNull(sample.machineCpu());
        assertTrue(sample.heapUsed() > 0);
        assertFalse(degraded.cpuAvailable());
    }

    @Test
    @DisplayName("the last good CPU reading stands while the OS sampler has nothing new")
    void holdsLastReadingThroughGaps() {
        JvmVitals vitals = held(Duration.ofSeconds(2));

        reading.set(0.42d);
        assertEquals(0.42d, vitals.sample().processCpu());

        // The OS answers -1 when asked again too soon; the monitor keeps showing 0.42.
        reading.set(-1d);
        nanos.set(Duration.ofMillis(100).toNanos());
        assertEquals(0.42d, vitals.sample().processCpu());
        nanos.set(Duration.ofMillis(1900).toNanos());
        assertEquals(0.42d, vitals.sample().processCpu());
    }

    @Test
    @DisplayName("a reading that goes stale becomes null rather than lingering as if current")
    void staleReadingExpires() {
        JvmVitals vitals = held(Duration.ofSeconds(2));

        reading.set(0.42d);
        vitals.sample();

        reading.set(-1d);
        nanos.set(Duration.ofMillis(2001).toNanos());
        assertNull(vitals.sample().processCpu());
    }

    @Test
    @DisplayName("a value that never arrived is null, not zero")
    void neverSeededIsNull() {
        assertNull(held(Duration.ofSeconds(2)).sample().processCpu());
    }

    @Test
    @DisplayName("an out-of-range reading is clamped to a ratio")
    void clampsToRatio() {
        JvmVitals vitals = held(Duration.ofSeconds(2));

        reading.set(1.5d);

        assertEquals(1d, vitals.sample().processCpu());
    }

    private JvmVitals held(Duration hold) {
        return new JvmVitals(ManagementFactory.getMemoryMXBean(), ManagementFactory.getThreadMXBean(),
                osStub(), nanos::get, hold);
    }

    /** Only getProcessCpuLoad and getCpuLoad are ever called, so a proxy is enough of a stub. */
    private com.sun.management.OperatingSystemMXBean osStub() {
        return (com.sun.management.OperatingSystemMXBean) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {com.sun.management.OperatingSystemMXBean.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProcessCpuLoad", "getCpuLoad" -> reading.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
