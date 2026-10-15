package net.bitsar.sonify.scenarios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scenario API for the servlet application.
 *
 * <p>The reactive application exposes the same paths through its own controller, because on an
 * event loop it matters a great deal which thread the work runs on, and that choice cannot be
 * expressed in a controller shared with a thread-per-request server.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/scenario")
public class ScenarioController {

    private final ScenarioService scenarios;

    public ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    /** The catalogue, so the list lives in one place rather than in a README that drifts. */
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

    /** One unit of work on the calling request thread. External load drivers hit this. */
    @GetMapping("/{id}/once")
    public ResponseEntity<String> once(@PathVariable("id") String id,
                                       @RequestParam(name = "user", defaultValue = "0") int user) {
        try {
            scenarios.once(id, user);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(unknown.getMessage());
        } catch (Exception failure) {
            return ResponseEntity.status(502).body(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /** Drives a scenario from inside this JVM. Convenient, but blind to the server's threads. */
    @GetMapping("/{id}/run")
    public ResponseEntity<ScenarioOutcome> run(@PathVariable("id") String id,
                                               @RequestParam(name = "users", defaultValue = "50") int users,
                                               @RequestParam(name = "seconds", defaultValue = "30") int seconds) {
        try {
            return ResponseEntity.ok(scenarios.runInJvm(id, ScenarioRequest.of(users, seconds)));
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/status")
    public String status() {
        return scenarios.status();
    }

    @GetMapping("/reset")
    public String reset() {
        return scenarios.reset();
    }

    @GetMapping("/warm")
    public String warm() throws Exception {
        scenarios.warm();
        return "cache warmed";
    }
}
