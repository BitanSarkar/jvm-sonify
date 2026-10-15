package net.bitsar.sonify.autoconfigure.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=servlet",
                "sonify.enabled=true",
                "sonify.tick=100ms",
                "sonify.max-clients=2"
        })
class SonifyMvcStreamIT {

    @LocalServerPort
    private int port;

    @Test
    @Timeout(120)
    @DisplayName("Spring MVC streams ticks, collections and contention over SSE")
    void streamsEveryEventType() {
        List<ServerSentEvent<String>> events =
                StreamAssertions.collectUnderLoad(port, Duration.ofSeconds(25));

        StreamAssertions.assertStreamIsHealthy("mvc", events);
    }

    @Test
    @Timeout(60)
    @DisplayName("the UI is served under the base path")
    void servesTheUi() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        client.get().uri("/sonify/").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(body -> assertThat(body).contains("JVM Sonify"));

        client.get().uri("/sonify/sonify.js").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("EventSource"));
    }

    @Test
    @Timeout(60)
    @DisplayName("a file that is not part of the UI cannot be read through the base path")
    void refusesUnknownAssets() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/sonify/application.properties").exchange()
                .expectStatus().isNotFound();
    }
}
