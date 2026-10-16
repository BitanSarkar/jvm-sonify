# Scenarios

Eighteen ways a well-built Spring Boot application degrades, reproduced against real PostgreSQL,
real Redis and a third-party API running as its own process.

**None of these are broken programs.** The repositories are paginated, the transactions are
scoped, the HTTP clients have connect and read timeouts with bounded retries, exponential backoff
and a circuit breaker, and the caches have a time to live. Every one would pass review, and
several are the textbook fix for a different problem.

They degrade anyway, because resilience against errors is not resilience against latency, and
correctness is not the same as scalability. Production systems rarely fail by crashing. They fail
by getting slower while every health check stays green.

---

## The application underneath

The scenarios drive an ordinary application. There is no "break the JVM" code anywhere in it.

```
OrderApiController          thin, no logic
      │
      ├─ OrderService ───────── OrderRepository ──────── PostgreSQL
      │                         (Spring Data JPA, lazy associations)
      ├─ CheckoutService ────── PaymentGatewayClient ─── upstream :8090
      ├─ CustomerCacheService ─ StringRedisTemplate ──── Redis
      │                       └ CustomerRepository ───── PostgreSQL
      ├─ InvoiceFormatter       (a shared SimpleDateFormat)
      └─ QuoteMemoizer          (a ConcurrentHashMap)
```

A scenario is one of these endpoints driven at a particular concurrency. The pathology emerges
from the service code, which is why you can recognise your own work in it.

---

## Running them

```bash
brew services start postgresql@16 && brew services start redis
```

```bash
java -cp ~/.sonify-build/sonify-scenarios/classes net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090
```

```bash
java -Xmx512m -jar ~/.sonify-build/sonify-demo/sonify-demo-0.1.0-SNAPSHOT.jar
```

```bash
java -Xmx512m -jar ~/.sonify-build/sonify-demo-reactive/sonify-demo-reactive-0.1.0-SNAPSHOT.jar
```

Paths assume the build output has been moved out of iCloud; a default build puts them under
`<module>/target/`. `scenarios.sh` checks both and prints whichever is right for your machine.

| command | what it does |
| --- | --- |
| `scenarios.sh list` | the catalogue |
| `scenarios.sh run <id> [users] [secs]` | drive from inside the JVM |
| `scenarios.sh http <id> [users] [secs]` | drive over HTTP from a separate process |
| `scenarios.sh compare <id> [users] [secs]` | the same scenario on Tomcat and on Netty |
| `scenarios.sh all [users] [secs]` | every scenario, with pauses and resets |

**Use `http` whenever the server is the subject.** Load from inside the JVM never touches the
Tomcat worker pool or the Netty event loops, so it cannot show either filling up.

---

## Database and persistence

### The N+1, three ways — start here

This is the most instructive thing in the project. Three implementations of one listing page,
**all returning identical results**, measured at 20 users for 10 seconds:

| `/api/orders?fetch=` | single call | under load | what it does |
| --- | --- | --- | --- |
| `batched` | **33ms** | **1936 req/s**, p50 8ms | two queries, any page size |
| `lazy` | 71ms | 337 req/s, p50 58ms | 101 queries |
| `join` | 585ms | **~0 req/s**, p50 35s | loads 40,000 orders into memory |

**`db-n-plus-one`** — a paginated, read-only listing that renders each order's lines. One query
for the page, then one more per order, because touching a lazy collection is a round trip that
looks like a field access. *Listen for: the hi-hat racing and the kick becoming a drum roll.*

**`db-fetch-join-paginated`** — the first thing anybody reaches for after being told to fix an
N+1, and **far worse than the bug**. A collection join multiplies rows, so the database cannot
apply the limit without losing lines; Hibernate therefore fetches every matching row and
paginates in memory. Asking for 100 orders loads 40,000 of them and 200,000 lines.
*Listen for: the drone climbing while everything else falls silent.*

**`db-n-plus-one-fixed`** — one query for the page of identifiers, which the database *can* limit
because nothing is joined, then one more for exactly those orders with their lines.

> Run all three back to back. The ear hears the difference before the numbers arrive.

### `db-transaction-spans-http`

A `@Transactional` checkout that calls the payment gateway while the transaction is open. The
timeout is set, the retries are bounded, the breaker works — and the connection is pinned for the
whole round trip. A pool of ten and a two-second gateway caps the application at five checkouts a
second, however many threads are free.

**`db-transaction-outside`** is the same work with the call moved outside the boundary. One line
of difference. *Watch: waiting threads still rise, but the pool stops being the limit.*

### `db-export-too-large`

An export that was fine against staging data. Hibernate holds every row it returns in the
persistence context, so the cost is the result set twice over. *Heap ramps, old collections
reclaim progressively less.*

---

## Third-party dependencies

All four use clients with timeouts, three bounded retries, exponential backoff with jitter, and a
circuit breaker that opens after repeated failures.

### `api-breaker-blind-to-slowness` — the one to study

The gateway is **slow, not failing**. Nothing errors, so the failure counter never rises, so the
breaker never opens, and every request thread waits out the full timeout.

From the outside it is indistinguishable from an outage: throughput collapses, error rate stays
at zero, every dashboard is green. *Pure breath pad. The silence is the diagnosis.*

### `api-timeout-too-generous`

A 30-second read timeout on a call whose p99 is 200ms. Not a missing timeout — a comfortable
round number nobody thought hard about, fifty times the real latency. It cannot fire soon enough
to protect the thread pool when the vendor degrades to five seconds.

### `api-retry-amplification`

Three attempts with exponential backoff and jitter, exactly as advised, against a gateway failing
seven times in ten. Each user request becomes up to three upstream requests, so a struggling
dependency receives several times the traffic it already could not serve.

### `api-payload-growth`

The vendor added a field and the response went from kilobytes to megabytes. The client is
unchanged and still correct. *The loudest scenario: allocation spikes, heap climbs, and the
long-pause alarm fires.*

---

## Caching

**`cache-healthy`** — look aside, fall through, populate with a TTL. Almost everything served from
Redis. **Play this first and last**; you cannot recognise an unhealthy system without hearing a
healthy one. *A calm drone with the melody running.*

**`cache-stampede`** — a hot key with a short TTL, recomputed under a lock so the database is not
stampeded. The lock is the recommended fix, and it moves the stampede onto the application.
Double-checked locking does not help: everyone had already missed before anyone acquired the lock.
*A dissonant cluster, and the melody cuts out.*

**`cache-cold-after-deploy`** — nothing is wrong; the cache is simply empty. The recovery is the
interesting part, since most scenarios only get worse.

**`cache-unbounded-keys`** — a `ConcurrentHashMap` memoiser, thread safe, atomic, reviewed and
approved. The key includes a per-request reference, so no entry is ever reused and nothing is
ever evicted. Days, not minutes, in production. *The drone climbs and the leak swell fires.*

---

## The concurrency model

Run these with `compare`, which drives identical external load at both servers:

```bash
./sonify-demo/scripts/scenarios.sh compare conc-thread-per-request 60 20
```

| | throughput | p50 | p99 |
| --- | --- | --- | --- |
| Tomcat, thread per request | 191.6 req/s | 309ms | 394ms |
| Netty, `block()` on the event loop | **24.9 req/s** | 2200ms | 4466ms |
| Netty, offloaded to `boundedElastic` | 189.5 req/s | 308ms | 399ms |

**`conc-block-event-loop`** is the frightening one. Eight cores means eight event loops, each
serving one 300ms call at a time: 8 ÷ 0.3s ≈ 26 a second. Throughput was not degraded by load, it
was **pinned to the event loop count** — and the thread count stays perfectly flat throughout, so
nothing grows to show the damage.

**`conc-offload-elastic`** is the same blocking call moved to `boundedElastic`, which is the
correct handling. Event loops stay free and throughput matches Tomcat exactly — the third row
above. Run it straight after `conc-block-event-loop` to hear the fix.

**`conc-thread-per-request`** is the servlet baseline: every request holds a platform thread for
its duration, correct and conventional, bounded by the container maximum.

**`conc-shared-lock`** — `SimpleDateFormat` is not thread safe, so the shared instance is guarded
by a lock, which is the fix every guide recommends. It is correct, and it serialises every request
in the application through one monitor. Measured: p50 **39µs**, max **4.26 seconds**, because
intrinsic locks are not fair. Watch `BLOCKED` climb while waiting stays flat — the mirror image of
pool exhaustion, same symptom to a user, completely different fix.

---

## How to read a scenario

1. **Throughput first.** Anything meant to hurt that still runs at thousands of requests a second
   is mis-calibrated, not mild.
2. **Which vital moved?** Most scenarios move one or two, and that pattern is the diagnosis.
3. **What stayed flat?** Often more informative — the blocked event loop is diagnosed entirely by
   what *doesn't* move.
4. **p99 against p50.** Fine p50 with a terrible p99 is queueing, not working.

## Two that run last

`db-fetch-join-paginated` and `api-timeout-too-generous` leave the JVM saturated for a while, so
anything following them measures the recovery rather than itself. `scenarios.sh all` already
orders them last, and `scenarios.sh ready` waits for the application to answer again.

## Picking six for a reel

- **Open calm**: `cache-healthy`.
- **Loud**: `api-payload-growth`, `db-export-too-large`, `cache-stampede`, `conc-shared-lock`.
- **Quiet and shocking**: `api-breaker-blind-to-slowness`, `conc-block-event-loop` — where the
  point is how little happens while the application dies.
- **The teaching moment**: the three-way N+1 comparison.

The quiet ones are the most educational and least impressive alone. They work when they follow
something loud.
