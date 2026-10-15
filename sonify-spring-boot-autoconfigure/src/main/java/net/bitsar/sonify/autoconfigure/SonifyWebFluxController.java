package net.bitsar.sonify.autoconfigure;

import java.time.Duration;
import net.bitsar.sonify.core.SonifyBroker;
import net.bitsar.sonify.core.SonifyCapacityException;
import net.bitsar.sonify.core.Subscription;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.json.SonifyEventJson;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The reactive counterpart of {@link SonifyMvcController}, mapped at the same path.
 *
 * <p>The subscription's queue is drained on a blocking-friendly scheduler, since polling it waits.
 */
@RestController
public class SonifyWebFluxController {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);

    private final SonifyBroker broker;
    private final Duration tick;

    public SonifyWebFluxController(SonifyBroker broker, Duration tick) {
        this.broker = broker;
        this.tick = tick;
    }

    @GetMapping(path = "${sonify.base-path:/sonify}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream() {
        return Flux.using(
                        broker::subscribe,
                        subscription -> Flux.<ServerSentEvent<String>>generate(sink -> emitNext(subscription, sink))
                                .subscribeOn(Schedulers.boundedElastic()),
                        Subscription::close)
                .onBackpressureLatest();
    }

    @ExceptionHandler(SonifyCapacityException.class)
    public ResponseEntity<String> tooManyClients(SonifyCapacityException full) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body(full.getMessage());
    }

    private void emitNext(Subscription subscription, reactor.core.publisher.SynchronousSink<ServerSentEvent<String>> sink) {
        try {
            SonifyEvent event = subscription.poll(POLL_TIMEOUT);
            if (event == null) {
                // A comment keeps proxies from closing an idle connection.
                sink.next(ServerSentEvent.<String>builder().comment("keepalive").build());
                return;
            }
            sink.next(ServerSentEvent.builder(SonifyEventJson.toJson(event))
                    .event(event.type())
                    .build());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sink.complete();
        }
    }

    Duration tick() {
        return tick;
    }
}
