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
                "spring.main.web-application-type=reactive",
                "sonify.enabled=true",
                "sonify.tick=100ms",
                "sonify.max-clients=2"
        })
class SonifyWebFluxStreamIT {

    @LocalServerPort
    private int port;

    @Test
    @Timeout(120)
    @DisplayName("WebFlux streams ticks, collections and contention over SSE")
    void streamsEveryEventType() {
        List<ServerSentEvent<String>> events =
                StreamAssertions.collectUnderLoad(port, Duration.ofSeconds(25));

        StreamAssertions.assertStreamIsHealthy("webflux", events);
    }

    @Test
    @Timeout(60)
    @DisplayName("the same UI is served on the reactive transport")
    void servesTheUi() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/sonify/").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(body -> assertThat(body).contains("JVM Sonify"));
    }
}
