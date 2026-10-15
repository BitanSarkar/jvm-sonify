package net.bitsar.sonify.demo.reactive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bitsar.sonify.scenarios.ScenarioCatalog;
import net.bitsar.sonify.scenarios.ScenarioOutcome;
import net.bitsar.sonify.scenarios.ScenarioRequest;
import net.bitsar.sonify.scenarios.ScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The scenario API on Netty, where the thread a call runs on is the whole story.
 *
 * <p>Every scenario here is blocking work, because the dependencies are blocking: JDBC and a
 * synchronous HTTP client. There are only two honest things to do with blocking work on an event
 * loop, and both are offered so they can be heard against each other.
 *
 * <ul>
 *   <li>{@code conc-block-event-loop} runs it straight on the event loop, which is the mistake.
 *       A handful of threads serve the entire application, so throughput collapses to that
 *       number while the thread count stays perfectly flat and nothing looks wrong.</li>
 *   <li>Everything else is offloaded to {@code boundedElastic}, which is the correct handling.
 *       The event loops stay free and an elastic pool absorbs the blocking work.</li>
 * </ul>
 */
@RestController
@RequestMapping("/scenario")
public class ReactiveScenarioController {

    private final ScenarioService scenarios;

    public ReactiveScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<Map<String, String>> catalogue() {
        return ScenarioCatalog.all().stream().map(scenario -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", scenario.id());
            entry.put("name", scenario.name());
            entry.put("category", scenario.category().label());
            entry.put("transport", scenario.transport().name());
            entry.put("situation", scenario.situation());
            entry.put("expectedGraph", scenario.expectedGraph());
            entry.put("expectedSound", scenario.expectedSound());
            return entry;
        }).toList();
    }

    /**
     * The deliberate mistake: blocking work executed directly on the Netty event loop.
     *
     * <p>A more specific mapping than the pattern below, so Spring routes this one here.
     */
    @GetMapping("/conc-block-event-loop/once")
    public Mono<ResponseEntity<String>> blockOnEventLoop(
            @RequestParam(name = "user", defaultValue = "0") int user) {
        // Called inline, on the calling thread, which here is an event loop thread.
        return Mono.just(perform("conc-block-event-loop", user, Thread.currentThread().getName()));
    }

    /** The same blocking work, moved off the event loop as it should be. */
    @GetMapping("/{id}/once")
    public Mono<ResponseEntity<String>> once(@PathVariable("id") String id,
                                             @RequestParam(name = "user", defaultValue = "0") int user) {
        return Mono.fromCallable(() -> perform(id, user, Thread.currentThread().getName()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/run")
    public Mono<ResponseEntity<ScenarioOutcome>> run(
            @PathVariable("id") String id,
            @RequestParam(name = "users", defaultValue = "50") int users,
            @RequestParam(name = "seconds", defaultValue = "30") int seconds) {
        return Mono.fromCallable(() ->
                        ResponseEntity.ok(scenarios.runInJvm(id, ScenarioRequest.of(users, seconds))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/status")
    public Mono<String> status() {
        return Mono.fromCallable(() -> scenarios.status() + ", server=netty");
    }

    @GetMapping("/reset")
    public Mono<String> reset() {
        return Mono.fromCallable(scenarios::reset).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/warm")
    public Mono<String> warm() {
        return Mono.fromCallable(() -> {
            scenarios.warm();
            return "cache warmed";
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<String> perform(String id, int user, String threadName) {
        try {
            scenarios.once(id, user);
            return ResponseEntity.ok("ok on " + threadName);
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(unknown.getMessage());
        } catch (Exception failure) {
            return ResponseEntity.status(502)
                    .body(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }
}
