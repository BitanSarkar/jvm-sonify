package net.bitsar.sonify.autoconfigure;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.bitsar.sonify.core.SonifyBroker;
import net.bitsar.sonify.core.SonifyCapacityException;
import net.bitsar.sonify.core.Subscription;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.json.SonifyEventJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams events to one browser over server-sent events.
 *
 * <p>A plain controller rather than an Actuator endpoint, because Actuator's web endpoints cannot
 * stream server-sent events cleanly.
 */
@RestController
public class SonifyMvcController implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SonifyMvcController.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);

    private final SonifyBroker broker;
    private final ExecutorService pumps;

    public SonifyMvcController(SonifyBroker broker) {
        this.broker = broker;
        this.pumps = Executors.newCachedThreadPool(pumpThreadFactory());
    }

    /**
     * Opens a stream. The broker caps concurrency, so at most {@code sonify.max-clients} of these
     * exist at once.
     */
    @GetMapping(path = "${sonify.base-path:/sonify}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Subscription subscription = broker.subscribe();
        // No timeout: the browser decides how long to watch, and the pump notices it leaving.
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        pumps.execute(() -> pump(subscription, emitter));
        return emitter;
    }

    @ExceptionHandler(SonifyCapacityException.class)
    public ResponseEntity<String> tooManyClients(SonifyCapacityException full) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body(full.getMessage());
    }

    private void pump(Subscription subscription, SseEmitter emitter) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                SonifyEvent event = subscription.poll(POLL_TIMEOUT);
                if (event == null) {
                    // A comment keeps proxies from closing an idle connection.
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    continue;
                }
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        // Already JSON: sent as text so the message converter cannot re-quote it.
                        .data(SonifyEventJson.toJson(event), MediaType.TEXT_PLAIN));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception disconnected) {
            LOG.debug("JVM Sonify: client disconnected ({})", disconnected.getMessage());
        } finally {
            subscription.close();
            try {
                emitter.complete();
            } catch (Exception alreadyClosed) {
                // The response was already finished by the container.
            }
        }
    }

    @Override
    public void destroy() {
        pumps.shutdownNow();
    }

    private static ThreadFactory pumpThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "sonify-sse-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
