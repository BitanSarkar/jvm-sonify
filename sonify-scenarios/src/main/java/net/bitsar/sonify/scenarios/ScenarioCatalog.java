package net.bitsar.sonify.scenarios;

import static net.bitsar.sonify.scenarios.Scenario.Category.CACHE;
import static net.bitsar.sonify.scenarios.Scenario.Category.CONCURRENCY;
import static net.bitsar.sonify.scenarios.Scenario.Category.DATABASE;
import static net.bitsar.sonify.scenarios.Scenario.Category.UPSTREAM;
import static net.bitsar.sonify.scenarios.Scenario.Transport.ANY;
import static net.bitsar.sonify.scenarios.Scenario.Transport.REACTIVE;
import static net.bitsar.sonify.scenarios.Scenario.Transport.SERVLET;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sixteen scenarios, each a pathology that really happens to real applications.
 *
 * <p>They are chosen to leave distinct signatures. Several are deliberately quiet: a pool that
 * has run dry or an event loop that is blocked produces almost no allocation, no collections and
 * no monitor contention, and that silence is itself the diagnosis.
 */
public final class ScenarioCatalog {

    private static final Map<String, Scenario> BY_ID = new LinkedHashMap<>();

    static {
        // --- database and connection pool ------------------------------------------------
        add(new Scenario("db-pool-exhaustion", "Connection pool exhaustion", DATABASE,
                "More concurrent users than the Hikari pool has connections, which is the single "
                        + "most common way a healthy application falls over under a traffic spike.",
                "Waiting threads climb and stay climbing while heap, allocation and CPU stay flat. "
                        + "Throughput plateaus at the pool size no matter how many users arrive.",
                "The breath pad swells as threads queue for a connection. Almost nothing else moves.",
                ANY));

        add(new Scenario("db-n-plus-one", "N+1 query storm", DATABASE,
                "An ORM-shaped access pattern: fetch a page of orders, then fetch the lines for "
                        + "each order one query at a time.",
                "Allocation climbs hard and young collections become constant. Heap saws rapidly.",
                "The hi-hat races and the kick turns into a drum roll.",
                ANY));

        add(new Scenario("db-unbounded-result", "Unbounded result set", DATABASE,
                "A query with no LIMIT that happened to be fine in staging, run against production "
                        + "data volumes.",
                "Heap ramps steeply towards the ceiling, old collections appear and reclaim little.",
                "The drone climbs and the leak swell fires when a collection frees almost nothing.",
                ANY));

        add(new Scenario("db-connection-leak", "Connection leak", DATABASE,
                "A code path that forgets to close its connection, so the pool drains slowly until "
                        + "the application stops being able to talk to the database at all.",
                "Waiting threads climb gradually rather than suddenly, then throughput falls off a "
                        + "cliff when the last connection is gone.",
                "The breath pad builds slowly and never recedes. The healthy melody stops.",
                ANY));

        // --- third-party API ---------------------------------------------------------------
        add(new Scenario("api-slow-upstream", "Slow upstream on a thread-per-request server", UPSTREAM,
                "A third-party API that normally answers in 50ms starts taking two seconds, while "
                        + "traffic stays the same.",
                "Thread count climbs towards the container maximum and flatlines there. CPU stays "
                        + "low because the threads are waiting, not working.",
                "The breath pad rises steadily. Very little else.",
                SERVLET));

        add(new Scenario("api-retry-storm", "Retry storm", UPSTREAM,
                "An upstream starts failing and the client retries three times with no backoff, so "
                        + "the failing dependency receives four times the load it already could not handle.",
                "Allocation and waiting threads step up in multiples as retries pile on.",
                "The breath pad rises in steps while the hi-hat quickens.",
                ANY));

        add(new Scenario("api-no-timeout", "Missing read timeout", UPSTREAM,
                "An HTTP client built without a read timeout, talking to an upstream that accepts "
                        + "the connection and then never answers.",
                "Threads enter the wait and never come back. The thread lane only grows.",
                "The breath pad rises and stays there. Nothing recovers.",
                SERVLET));

        add(new Scenario("api-huge-payload", "Oversized upstream payload", UPSTREAM,
                "An upstream that answers with several megabytes of JSON per call, parsed into "
                        + "objects on every request.",
                "Allocation spikes hard, heap climbs, and large buffers push G1 into humongous "
                        + "allocations and old collections.",
                "Hi-hat races, kicks get heavier, and long pauses trip the alarm.",
                ANY));

        // --- redis and caching ---------------------------------------------------------------
        add(new Scenario("cache-hit-path", "Healthy cache hit path", CACHE,
                "The system working properly: almost everything served from Redis, the database "
                        + "barely touched. This is the reference both ears and eyes need.",
                "Everything flat and low. Steady modest allocation, occasional small collections.",
                "A calm drone with the pentatonic melody running. This is what healthy sounds like.",
                ANY));

        add(new Scenario("cache-stampede", "Cache stampede", CACHE,
                "A hot key expires and every in-flight request recomputes it at once, guarded by a "
                        + "synchronized block that turns a cache miss into a queue.",
                "A wall of contention, with threads blocked on one monitor for seconds at a time.",
                "A dissonant cluster, and the healthy melody cuts out entirely.",
                ANY));

        add(new Scenario("cache-cold", "Cold cache after a deploy", CACHE,
                "A restart or a flush leaves the cache empty, so every request falls through to the "
                        + "database until it refills.",
                "Sustained elevated allocation and pool pressure that slowly subsides as hit rate "
                        + "recovers. The recovery is the interesting part.",
                "Busy hi-hat and frequent kicks, easing off as the cache warms.",
                ANY));

        add(new Scenario("cache-unbounded-local", "Unbounded in-process cache", CACHE,
                "A local HashMap used as a cache with no eviction and no size limit, keyed by "
                        + "something unbounded such as a session or a request id.",
                "Heap climbs steadily and never returns. Collections run more often and reclaim "
                        + "less each time.",
                "The drone climbs and the leak swell fires repeatedly.",
                ANY));

        // --- concurrency model ------------------------------------------------------------------
        add(new Scenario("conc-thread-per-request", "Thread-per-request under load", CONCURRENCY,
                "Blocking I/O on a servlet container: every concurrent request holds a platform "
                        + "thread for its whole duration. Run this on both servers to compare.",
                "Thread count tracks concurrency directly and climbs towards the container maximum.",
                "The breath pad rises with the waiting threads.",
                SERVLET));

        add(new Scenario("conc-block-event-loop", "Blocking call on the event loop", CONCURRENCY,
                "The classic WebFlux mistake: calling block() inside a reactive handler, so the "
                        + "handful of Netty event loop threads are held by one request each.",
                "The frightening one. Thread count stays perfectly flat while throughput collapses "
                        + "to the number of event loops, because nothing grows to show the damage.",
                "Near silence, with the breath pad barely moving. The absence is the symptom.",
                REACTIVE));

        add(new Scenario("conc-offload-elastic", "Blocking work offloaded correctly", CONCURRENCY,
                "The same blocking call, moved onto boundedElastic as it should be, so the event "
                        + "loops stay free. The counterpart that shows the fix working.",
                "Event loops stay free and throughput holds up while an elastic pool grows to "
                        + "absorb the blocking work.",
                "The breath pad rises but the melody keeps running, because nothing is starved.",
                REACTIVE));

        add(new Scenario("conc-lock-convoy", "Lock convoy on a hot path", CONCURRENCY,
                "A synchronized method on a shared object sitting in the request path, which is "
                        + "fine at low traffic and becomes a queue the moment concurrency rises.",
                "Sustained contention across many threads, with individual waits running into "
                        + "seconds because intrinsic locks are not fair.",
                "A thick dissonant cluster for as long as the load lasts.",
                ANY));
    }

    private ScenarioCatalog() {
    }

    private static void add(Scenario scenario) {
        BY_ID.put(scenario.id(), scenario);
    }

    public static List<Scenario> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Optional<Scenario> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<Scenario> runnableOn(Scenario.Transport transport) {
        return all().stream()
                .filter(scenario -> scenario.transport() == ANY || scenario.transport() == transport)
                .toList();
    }
}
