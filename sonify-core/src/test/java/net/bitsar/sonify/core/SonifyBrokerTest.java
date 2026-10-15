package net.bitsar.sonify.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.bitsar.sonify.core.event.ContentionEvent;
import net.bitsar.sonify.core.event.GcEvent;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.event.TickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SonifyBrokerTest {

    private static final Object MONITOR = new Object();

    private final SonifyConfig config = SonifyConfig.builder()
            .tick(Duration.ofMillis(50))
            .gracePeriod(Duration.ofMillis(200))
            .contentionThreshold(Duration.ofMillis(10))
            .maxClients(2)
            .build();

    private final SonifyBroker broker = new SonifyBroker(config);

    @AfterEach
    void tearDown() {
        broker.close();
    }

    @Test
    @DisplayName("an application nobody is watching measures nothing")
    void idleUntilFirstSubscriber() {
        SonifyStatus idle = broker.status();

        assertFalse(idle.running());
        assertEquals(0, idle.clients());
        assertEquals(0L, idle.eventsPublished());
    }

    @Test
    @Timeout(20)
    @DisplayName("subscribing starts the pipeline and ticks begin to arrive")
    void firstSubscriberStartsTicking() throws InterruptedException {
        try (Subscription subscription = broker.subscribe()) {
            assertTrue(broker.status().running());
            assertEquals(1, broker.status().clients());

            TickEvent tick = awaitEvent(subscription, TickEvent.class, Duration.ofSeconds(5));

            assertNotNull(tick, "expected a tick within 5s");
            assertTrue(tick.heapUsed() > 0, "heap should be reported");
            assertTrue(tick.threads() > 0, "threads should be reported");
            assertEquals("tick", tick.type());
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("Flight Recorder comes up on this JVM and reports real GC and contention")
    void streamsRealJfrEvents() throws Exception {
        try (Subscription subscription = broker.subscribe()) {
            assertTrue(broker.status().jfrAvailable(),
                    "JFR should be available on a standard JDK; the broker must still run if it is not");

            List<SonifyEvent> seen = new ArrayList<>();
            Thread load = startLoad();
            try {
                long deadline = System.currentTimeMillis() + 15_000;
                boolean sawGc = false;
                boolean sawContention = false;
                while (System.currentTimeMillis() < deadline && !(sawGc && sawContention)) {
                    SonifyEvent event = subscription.poll(Duration.ofMillis(200));
                    if (event == null) {
                        continue;
                    }
                    seen.add(event);
                    sawGc |= event instanceof GcEvent;
                    sawContention |= event instanceof ContentionEvent;
                }
                assertTrue(sawGc, "expected at least one GC event, saw " + summarise(seen));
                assertTrue(sawContention, "expected at least one contention event, saw " + summarise(seen));
            } finally {
                load.interrupt();
                load.join(Duration.ofSeconds(5).toMillis());
            }

            GcEvent gc = seen.stream().filter(GcEvent.class::isInstance).map(GcEvent.class::cast).findFirst().orElseThrow();
            assertNotNull(gc.name());
            assertFalse(gc.name().isBlank());
            assertTrue(gc.pauseMs() >= 0d);

            ContentionEvent contention = seen.stream().filter(ContentionEvent.class::isInstance)
                    .map(ContentionEvent.class::cast).findFirst().orElseThrow();
            assertNotNull(contention.thread());
            assertTrue(contention.durationMs() >= 10d,
                    "the 10ms threshold should hold, got " + contention.durationMs());
        }
    }

    @Test
    @DisplayName("the client limit is enforced instead of degrading everyone's stream")
    void enforcesMaxClients() {
        try (Subscription first = broker.subscribe(); Subscription second = broker.subscribe()) {
            assertNotNull(first);
            assertNotNull(second);
            assertThrows(SonifyCapacityException.class, broker::subscribe);
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("the pipeline stops once the last client has gone and the grace period elapses")
    void stopsAfterGracePeriod() throws InterruptedException {
        Subscription subscription = broker.subscribe();
        assertTrue(broker.status().running());
        subscription.close();

        assertTrue(broker.status().running(), "should still be running during the grace period");

        long deadline = System.currentTimeMillis() + 10_000;
        while (broker.status().running() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertFalse(broker.status().running(), "pipeline should have stopped after the grace period");
        assertEquals(0, broker.status().clients());
    }

    @Test
    @Timeout(20)
    @DisplayName("a client that reconnects inside the grace period keeps the same pipeline")
    void reconnectWithinGracePeriodCancelsShutdown() throws InterruptedException {
        broker.subscribe().close();
        Thread.sleep(50);

        try (Subscription reconnected = broker.subscribe()) {
            assertTrue(broker.status().running());
            Thread.sleep(config.gracePeriod().toMillis() + 300);
            assertTrue(broker.status().running(), "an active client must not be cut off");
            assertNotNull(reconnected);
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("every connected client sees the same events")
    void fansOutToAllClients() throws InterruptedException {
        try (Subscription first = broker.subscribe(); Subscription second = broker.subscribe()) {
            assertNotNull(awaitEvent(first, TickEvent.class, Duration.ofSeconds(5)));
            assertNotNull(awaitEvent(second, TickEvent.class, Duration.ofSeconds(5)));
        }
    }

    private static String summarise(List<SonifyEvent> seen) {
        return seen.stream().map(SonifyEvent::type).distinct().toList().toString();
    }

    private static <T extends SonifyEvent> T awaitEvent(Subscription subscription, Class<T> type, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            SonifyEvent event = subscription.poll(Duration.ofMillis(100));
            if (type.isInstance(event)) {
                return type.cast(event);
            }
        }
        return null;
    }

    /** Allocates hard and fights over one monitor, so GC and contention both become observable. */
    private static Thread startLoad() {
        Thread supervisor = new Thread(() -> {
            List<Thread> workers = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                Thread worker = new Thread(() -> {
                    List<byte[]> garbage = new ArrayList<>();
                    while (!Thread.currentThread().isInterrupted()) {
                        for (int batch = 0; batch < 256; batch++) {
                            garbage.add(new byte[16 * 1024]);
                        }
                        garbage.clear();
                        synchronized (MONITOR) {
                            try {
                                Thread.sleep(25);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }, "sonify-test-load-" + index);
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
            try {
                Thread.sleep(Duration.ofMinutes(1).toMillis());
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                workers.forEach(Thread::interrupt);
            }
        }, "sonify-test-load");
        supervisor.setDaemon(true);
        supervisor.start();
        return supervisor;
    }
}
