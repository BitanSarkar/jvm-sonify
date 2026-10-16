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
 * Scenarios built on ordinary application code.
 *
 * <p>None of these are broken programs. The repositories are paginated, the transactions are
 * scoped, the HTTP clients have timeouts and bounded retries with backoff and a circuit breaker,
 * and the caches have time to live. Every one of them would pass review, and several are the
 * textbook fix for a different problem.
 *
 * <p>They degrade anyway, because resilience against errors is not resilience against latency,
 * and correctness is not the same as scalability. That is the point: production systems rarely
 * fail by crashing. They fail by getting slower while every health check stays green.
 */
public final class ScenarioCatalog {

    private static final Map<String, Scenario> BY_ID = new LinkedHashMap<>();

    static {
        // --- database and persistence ---------------------------------------------------------
        add(new Scenario("db-n-plus-one", "Lazy association on a listing page", DATABASE,
                "A paginated, read-only listing that renders each order's lines. The repository "
                        + "call is one query; the loop that follows is a hundred more, because "
                        + "touching a lazy collection is a round trip that looks like a field access.",
                "Allocation climbs and young collections become constant. Throughput falls to a "
                        + "fraction of the fetch-joined version at identical load.",
                "The hi-hat races and the kick becomes a drum roll.",
                ANY));

        add(new Scenario("db-fetch-join-paginated", "The obvious fix, which is worse", DATABASE,
                "A join fetch with pagination, which is the first thing anybody reaches for after "
                        + "being told to fix an N+1. A collection join multiplies rows, so the "
                        + "database cannot apply the limit, and Hibernate loads every matching "
                        + "order and every line before taking the first hundred in memory.",
                "Heap climbs hard and throughput collapses. Measured on this data: a single "
                        + "request takes 585ms against 33ms for the correct fix, and under twenty "
                        + "concurrent users throughput falls to effectively zero. It also leaves "
                        + "the JVM too busy to measure anything else for a while, so it runs last.",
                "The drone climbs while everything else falls silent. The application has stopped.",
                ANY));

        add(new Scenario("db-n-plus-one-fixed", "The fix that actually works", DATABASE,
                "One query for the page of identifiers, which the database can limit because "
                        + "nothing is joined, then one more for exactly those orders with their "
                        + "lines. Two queries for any page size.",
                "Measured against the other two on identical load: 1936 requests a second at p50 "
                        + "8ms, against 337 a second at p50 58ms for the N+1 and effectively zero "
                        + "for the join fetch. Five to six times the throughput of the bug, and "
                        + "orders of magnitude better than the obvious fix.",
                "Noticeably calmer. Run all three back to back; this is the A/B worth hearing.",
                ANY));

        add(new Scenario("db-transaction-spans-http", "Transaction held across a gateway call", DATABASE,
                "A @Transactional checkout that calls the payment gateway while the transaction "
                        + "is open. The timeout is set, the retries are bounded, the breaker works. "
                        + "The connection is still pinned for the whole round trip.",
                "Threads waiting climbs and stays there while CPU and heap sit flat. Throughput "
                        + "caps at the pool size divided by the gateway's latency, not at anything "
                        + "about your hardware.",
                "The breath pad swells and very little else moves.",
                ANY));

        add(new Scenario("db-transaction-outside", "The same checkout, transaction narrowed", DATABASE,
                "The gateway call moved outside the transaction boundary. One line of difference, "
                        + "and the connection is released before the wait begins.",
                "Waiting threads still rise, because the gateway is still slow, but the pool stops "
                        + "being the limit and throughput recovers.",
                "The pad still swells; the application underneath keeps working.",
                ANY));

        add(new Scenario("db-export-too-large", "Export sized for staging", DATABASE,
                "An export that was fine against a staging database and is run against production "
                        + "volumes. Hibernate holds every row it returns in the persistence context.",
                "Heap ramps steeply, old collections appear and reclaim progressively less.",
                "The drone climbs; the leak swell fires when a collection frees almost nothing.",
                ANY));

        // --- third-party dependencies -----------------------------------------------------------
        add(new Scenario("api-timeout-too-generous", "A timeout nobody thought hard about", UPSTREAM,
                "A shipping client with a thirty second read timeout, which is fifty times the "
                        + "call's real latency. Not a missing timeout, a comfortable round number. "
                        + "When the vendor degrades to five seconds it cannot fire soon enough to "
                        + "save the thread pool.",
                "Thread count climbs towards the container maximum and flatlines. CPU stays low "
                        + "because nothing is working.",
                "The breath pad rises steadily.",
                SERVLET));

        add(new Scenario("api-retry-amplification", "Correct retries, multiplied load", UPSTREAM,
                "Three bounded attempts with exponential backoff and jitter, exactly as advised, "
                        + "against a gateway failing seven times in ten. Each user request becomes "
                        + "up to three upstream requests, so a struggling dependency receives "
                        + "several times the traffic it already could not serve.",
                "Waiting threads step up in multiples; the breaker eventually opens and the error "
                        + "rate falls while latency stays bad.",
                "The pad rises in steps.",
                ANY));

        add(new Scenario("api-breaker-blind-to-slowness", "A circuit breaker that never opens", UPSTREAM,
                "The same client and the same breaker, against a gateway that is slow rather than "
                        + "failing. Nothing errors, so the failure counter never rises, so the "
                        + "breaker stays closed and every thread waits out the full timeout.",
                "Identical to an outage from the outside: throughput collapses, errors stay at "
                        + "zero, and every dashboard is green.",
                "Pure breath pad. The silence is the diagnosis.",
                ANY));

        add(new Scenario("api-payload-growth", "A response that grew", UPSTREAM,
                "The vendor added a field and the quote response went from kilobytes to megabytes. "
                        + "The client is unchanged and still correct.",
                "Allocation spikes, heap climbs, large buffers push G1 into humongous allocations "
                        + "and old collections.",
                "Hi-hat racing, heavy kicks, and the long-pause alarm firing.",
                ANY));

        // --- caching -------------------------------------------------------------------------------
        add(new Scenario("cache-healthy", "The cache doing its job", CACHE,
                "Look aside, fall through on a miss, populate with a time to live. Almost every "
                        + "request served from Redis. This is the reference both ears and eyes need.",
                "Everything low and flat.",
                "A calm drone with the melody running. This is what healthy sounds like.",
                ANY));

        add(new Scenario("cache-stampede", "A guarded recompute under load", CACHE,
                "A hot key with a short time to live, recomputed under a lock so the database is "
                        + "not stampeded. The lock is the recommended fix, and it moves the "
                        + "stampede from the database onto the application.",
                "A wall of contention with threads blocked on one monitor for seconds.",
                "A dissonant cluster, and the melody cuts out.",
                ANY));

        add(new Scenario("cache-cold-after-deploy", "An empty cache after a restart", CACHE,
                "Nothing is wrong with the cache; it is simply empty, so every request falls "
                        + "through until it refills. The first minutes after a deploy.",
                "Sustained elevated allocation and pool pressure, subsiding as the hit rate climbs. "
                        + "The recovery is the interesting part.",
                "Busy, easing off as the cache warms.",
                ANY));

        add(new Scenario("cache-unbounded-keys", "A memoiser keyed by something unique", CACHE,
                "A ConcurrentHashMap used to avoid pricing the same request twice. Thread safe, "
                        + "atomic, reviewed and approved. The key includes a per-request reference, "
                        + "so no entry is ever reused and nothing is ever evicted.",
                "Heap climbs steadily and never returns. Collections run more often and reclaim "
                        + "less each time. Days, not minutes, in production.",
                "The drone climbs and the leak swell fires repeatedly.",
                ANY));

        // --- the concurrency model ---------------------------------------------------------------------
        add(new Scenario("conc-thread-per-request", "Blocking I/O on a servlet container", CONCURRENCY,
                "Every concurrent request holds a platform thread for its whole duration. Correct, "
                        + "conventional, and bounded by the container's maximum.",
                "Thread count tracks concurrency and climbs towards the ceiling.",
                "The breath pad rises with the waiting threads.",
                SERVLET));

        add(new Scenario("conc-block-event-loop", "The same call on an event loop", CONCURRENCY,
                "A blocking client called from a reactive handler. The frightening one: throughput "
                        + "collapses to the number of event loops while the thread count stays "
                        + "perfectly flat, so nothing grows to show the damage.",
                "Everything flat. Throughput pinned to the event loop count.",
                "Near silence. The absence is the symptom.",
                REACTIVE));

        add(new Scenario("conc-offload-elastic", "The same call, offloaded properly", CONCURRENCY,
                "The blocking work moved to boundedElastic so the event loops stay free. The "
                        + "counterpart that shows the fix working.",
                "Event loops stay free, an elastic pool absorbs the work, throughput holds.",
                "The pad rises but the melody keeps running.",
                REACTIVE));

        add(new Scenario("conc-shared-lock", "The textbook fix becoming the bottleneck", CONCURRENCY,
                "SimpleDateFormat is not thread safe, so the shared instance is guarded by a lock, "
                        + "which is the fix every guide recommends. It is correct, and it serialises "
                        + "every request in the application through one monitor.",
                "Threads BLOCKED climbs while waiting stays flat, which is the mirror image of pool "
                        + "exhaustion. Individual waits run to seconds because intrinsic locks are "
                        + "not fair.",
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
