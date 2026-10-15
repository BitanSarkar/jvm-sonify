package net.bitsar.sonify.scenarios;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.bitsar.sonify.scenarios.cache.CacheWorkload;
import net.bitsar.sonify.scenarios.concurrency.ConcurrencyWorkload;
import net.bitsar.sonify.scenarios.db.DatabaseWorkload;
import net.bitsar.sonify.scenarios.upstream.UpstreamWorkload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps scenario ids onto the real work, and remembers which one is running.
 *
 * <p>Two ways to drive a scenario, and the difference matters. {@link #runInJvm} spawns its own
 * threads inside the application, which is convenient and enough for anything whose signature is
 * about the JVM itself. It does not touch the server's thread pool at all, so it cannot show the
 * difference between a servlet container and an event loop; for those, load has to arrive over
 * HTTP from outside and each request is one call to {@link #once}.
 */
@Service
public class ScenarioService {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioService.class);

    private final DatabaseWorkload database;
    private final CacheWorkload cache;
    private final UpstreamWorkload upstream;
    private final ConcurrencyWorkload concurrency;
    private final ScenarioRunner runner = new ScenarioRunner();

    private final Map<String, Unit> units = new LinkedHashMap<>();
    private final AtomicReference<String> active = new AtomicReference<>(null);

    public ScenarioService(DatabaseWorkload database,
                           CacheWorkload cache,
                           UpstreamWorkload upstream,
                           ConcurrencyWorkload concurrency) {
        this.database = database;
        this.cache = cache;
        this.upstream = upstream;
        this.concurrency = concurrency;
        register();
    }

    private void register() {
        units.put("db-pool-exhaustion", database::poolExhaustion);
        units.put("db-n-plus-one", database::nPlusOne);
        units.put("db-unbounded-result", database::unboundedResult);
        units.put("db-connection-leak", database::connectionLeak);

        units.put("api-slow-upstream", upstream::slowUpstream);
        units.put("api-retry-storm", upstream::retryStorm);
        units.put("api-no-timeout", upstream::missingTimeout);
        units.put("api-huge-payload", upstream::hugePayload);

        units.put("cache-hit-path", cache::cacheHitPath);
        units.put("cache-stampede", cache::cacheStampede);
        units.put("cache-cold", cache::coldCache);
        units.put("cache-unbounded-local", cache::unboundedLocalCache);

        units.put("conc-thread-per-request", concurrency::blockingCall);
        units.put("conc-block-event-loop", concurrency::blockingCall);
        units.put("conc-offload-elastic", concurrency::blockingCall);
        units.put("conc-lock-convoy", userId -> concurrency.lockConvoy(userId));
    }

    /** Performs exactly one unit of a scenario's work, on the calling thread. */
    public void once(String scenarioId, int userId) throws Exception {
        Unit unit = units.get(scenarioId);
        if (unit == null) {
            throw new IllegalArgumentException("unknown scenario: " + scenarioId);
        }
        unit.run(userId);
    }

    /** Drives a scenario from threads inside this JVM. */
    public ScenarioOutcome runInJvm(String scenarioId, ScenarioRequest request) {
        Scenario scenario = ScenarioCatalog.find(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario: " + scenarioId));
        Unit unit = units.get(scenarioId);
        active.set(scenarioId);
        LOG.info("starting scenario {} with {} users for {}s",
                scenarioId, request.users(), request.duration().toSeconds());
        try {
            return runner.run(scenario, request, userId -> {
                try {
                    unit.run(userId);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        } finally {
            active.set(null);
        }
    }

    public String activeScenario() {
        return active.get();
    }

    /** Puts the application back to a known state between scenarios. */
    public String reset() {
        int connections = database.releaseLeaks();
        int localEntries = cache.localCacheSize();
        cache.reset();
        System.gc();
        return "released " + connections + " leaked connections, cleared " + localEntries
                + " local cache entries and the Redis keyspace";
    }

    public String status() {
        return "active=" + (active.get() == null ? "none" : active.get())
                + ", leakedConnections=" + database.leakedCount()
                + ", localCacheEntries=" + cache.localCacheSize()
                + ", cacheHitRate=" + cache.hitRate()
                + ", upstream=" + upstream.stats();
    }

    public void warm() throws Exception {
        cache.warm();
    }

    /** One unit of a scenario's work. */
    @FunctionalInterface
    private interface Unit {
        void run(int userId) throws Exception;
    }
}
