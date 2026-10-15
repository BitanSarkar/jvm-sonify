package net.bitsar.sonify.autoconfigure.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Shared helpers for the MVC and WebFlux stream tests, so both assert exactly the same things.
 *
 * <p>Fields are read with small regular expressions rather than a JSON library on purpose. Spring
 * Boot 4 moved from Jackson 2 to Jackson 3, so a test written against either mapper would only
 * compile on one of the two lines this library supports. The payload is a format this project
 * writes by hand and pins with unit tests, so reading it back without a mapper is reasonable here.
 */
final class StreamAssertions {

    private static final Object MONITOR = new Object();

    private StreamAssertions() {
    }

    /** Opens the stream and collects frames for {@code window}, provoking load meanwhile. */
    static List<ServerSentEvent<String>> collectUnderLoad(int port, Duration window) {
        Thread load = startLoad(window.plusSeconds(2));
        try {
            Flux<ServerSentEvent<String>> stream = WebClient.create("http://localhost:" + port)
                    .get()
                    .uri("/sonify/stream")
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                    });
            List<ServerSentEvent<String>> collected =
                    stream.take(window).collectList().block(window.plusSeconds(20));
            return collected == null ? List.of() : collected;
        } finally {
            load.interrupt();
        }
    }

    static Map<String, List<String>> byType(List<ServerSentEvent<String>> events) {
        return events.stream()
                .filter(event -> event.event() != null && event.data() != null)
                .collect(Collectors.groupingBy(ServerSentEvent::event,
                        Collectors.mapping(ServerSentEvent::data, Collectors.toList())));
    }

    static String text(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        assertThat(matcher.find()).as("expected a string field %s in %s", field, json).isTrue();
        return matcher.group(1);
    }

    static double number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        assertThat(matcher.find()).as("expected a numeric field %s in %s", field, json).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    static boolean has(String json, String field) {
        return json.contains("\"" + field + "\":");
    }

    /** Asserts the shape every transport must produce, so neither can drift from the other. */
    static void assertStreamIsHealthy(String transport, List<ServerSentEvent<String>> events) {
        assertThat(events).as("%s: expected frames on the stream", transport).isNotEmpty();

        Map<String, List<String>> byType = byType(events);
        assertThat(byType).as("%s: expected tick events, saw %s", transport, byType.keySet())
                .containsKey("tick");

        String tick = byType.get("tick").get(0);
        assertThat(text(tick, "type")).isEqualTo("tick");
        assertThat(number(tick, "t")).isPositive();
        assertThat(number(tick, "heapUsed")).as("%s: heap should be reported", transport).isPositive();
        assertThat(number(tick, "threads")).isPositive();
        assertThat(has(tick, "heapCommitted")).isTrue();
        assertThat(has(tick, "allocMBps")).isTrue();
        assertThat(has(tick, "cpu")).isTrue();
        assertThat(has(tick, "threadDetail")).isTrue();

        assertThat(byType).as("%s: load should have provoked a collection, saw %s",
                transport, byType.keySet()).containsKey("gc");
        String gc = byType.get("gc").get(0);
        assertThat(text(gc, "name")).isNotBlank();
        assertThat(number(gc, "pauseMs")).isNotNegative();

        assertThat(byType).as("%s: load should have provoked contention, saw %s",
                transport, byType.keySet()).containsKey("contention");
        String contention = byType.get("contention").get(0);
        assertThat(text(contention, "thread")).isNotBlank();
        assertThat(text(contention, "monitor")).isNotBlank();
        assertThat(number(contention, "durationMs"))
                .as("%s: the 10ms threshold should hold", transport)
                .isGreaterThanOrEqualTo(10d);
    }

    /** Allocates hard and queues threads on one monitor, so GC and contention both occur. */
    private static Thread startLoad(Duration duration) {
        Thread supervisor = new Thread(() -> {
            List<Thread> workers = new ArrayList<>();
            long deadline = System.currentTimeMillis() + duration.toMillis();
            for (int index = 0; index < 8; index++) {
                Thread worker = new Thread(() -> {
                    List<byte[]> garbage = new ArrayList<>();
                    while (System.currentTimeMillis() < deadline
                            && !Thread.currentThread().isInterrupted()) {
                        for (int batch = 0; batch < 512; batch++) {
                            garbage.add(new byte[16 * 1024]);
                        }
                        garbage.clear();
                        synchronized (MONITOR) {
                            try {
                                Thread.sleep(30);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }, "sonify-it-load-" + index);
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                workers.forEach(Thread::interrupt);
            }
        }, "sonify-it-load");
        supervisor.setDaemon(true);
        supervisor.start();
        return supervisor;
    }
}
