# Scenarios

Sixteen pathologies that genuinely happen to production applications, reproduced against real
PostgreSQL, real Redis and a third-party API running as its own process. Nothing is simulated
with sleeps: the Hikari pool really runs dry, the N+1 really issues a query per row, and the
upstream really holds the socket open.

---

## Running them

```bash
brew services start postgresql@16
brew services start redis
```

```bash
java -cp sonify-scenarios/target/classes net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090
```

```bash
java -Xmx512m -jar sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar
```

```bash
java -Xmx512m -jar sonify-demo-reactive/target/sonify-demo-reactive-0.1.0-SNAPSHOT.jar
```

If the build output has been moved out of a synced folder (see the root README), the jars are
under `~/.sonify-build/<module>/` rather than `<module>/target/`. `scenarios.sh` checks both and
prints the right commands for your machine when a prerequisite is missing.

Then open <http://localhost:8080/sonify/>, press **Play sound**, and:

| command | what it does |
| --- | --- |
| `scenarios.sh list` | the catalogue |
| `scenarios.sh run <id> [users] [secs]` | drive from inside the JVM |
| `scenarios.sh http <id> [users] [secs]` | drive over HTTP from outside |
| `scenarios.sh compare <id> [users] [secs]` | the same scenario on Tomcat and on Netty |
| `scenarios.sh all [users] [secs]` | every scenario in order, with pauses and resets |
| `scenarios.sh ready` | wait until the application answers again |

### Which driver to use

`run` spawns threads **inside** the application. Convenient, and enough for anything whose
signature is about the JVM itself.

`http` drives real connections **from a separate process**. Use it whenever the server's own
concurrency model is the subject, because load from inside never touches the Tomcat worker pool
or the Netty event loops and so cannot show either filling up.

The difference is not small. A lock convoy driven in-process ran at 25,424 req/s; over HTTP the
server bounds it long before the lock does.

---

## How to read a scenario

Four questions, in order:

1. **Throughput.** Is it actually biting? A scenario that is supposed to hurt and still runs at
   thousands of requests a second is mis-calibrated, not mild.
2. **Which vital moved?** Heap, allocation, CPU, contention, stuck threads — most scenarios move
   exactly one or two, and that pattern is the diagnosis.
3. **What stayed flat?** Often more informative. The blocked event loop is diagnosed entirely by
   what *doesn't* move.
4. **p99 against p50.** A scenario where p50 is fine and p99 is terrible is queueing, not working.

---

## Database and connection pool

The pool is deliberately small (10 connections). Most of these are about what happens when
demand exceeds it, which is the most common way a healthy application falls over.

### `db-pool-exhaustion`

**In production.** A traffic spike arrives and there are more concurrent requests than the pool
has connections. Every surplus request queues inside Hikari.

**What to watch.** Stuck threads climb and stay climbing while heap, allocation and CPU stay
flat. Throughput plateaus at whatever the pool can sustain no matter how many users arrive.

**What to listen for.** The breath pad swells. Almost nothing else moves — and that near-silence
with a rising pad is the signature.

**Measured** (40 users, 12s): stuck 54 → 85, io-wait 40 threads, allocation 5 MB/s, 2 collections.

**The lesson.** Pool exhaustion looks like an idle application on every resource graph. CPU is
low, memory is flat, and nothing is obviously wrong — because nothing is *working*.

### `db-n-plus-one`

**In production.** A listing page fetches a hundred rows, then lazily loads a relation for each
one: 101 queries where 2 would do. The commonest performance bug there is.

**What to watch.** Allocation climbs hard and young collections become constant. Heap saws
rapidly.

**What to listen for.** The hi-hat races and the kick turns into a drum roll.

**Measured** (20 users over HTTP): 1011 req/s, p50 16ms, p99 85ms. The same code shaped as a
six-row lookup ran at 6272 req/s with p50 2ms — the pathology is entirely in the row count.

### `db-unbounded-result`

**In production.** A query with no `LIMIT` that was fine against staging data, run against
production volumes and materialised into a list.

**What to watch.** Heap ramps steeply, old collections appear and reclaim little.

**What to listen for.** The drone climbs, and the leak swell fires when a collection frees almost
nothing.

**Measured**: heap 27 → 440 MB, allocation 4025 MB/s, 785 collections in 12 seconds.

### `db-connection-leak`

**In production.** A code path that forgets to close its connection. The pool drains slowly until
the application cannot talk to the database at all.

**What to watch.** Stuck threads climb gradually rather than suddenly, then throughput falls off
a cliff when the last connection is gone.

**What to listen for.** The breath pad builds slowly and never recedes. The healthy melody stops.

**Measured**: stuck 25 → 65 against a resting 25.

**Worth knowing.** This is the scenario that exposed the biggest flaw in the tool: before
instantaneous thread states it reported *five* parked threads while thirty sat stuck, because JFR
reports a wait only when it ends. See [FINDINGS.md](FINDINGS.md) §7.

---

## Third-party API

Calls go to a real separate process over a real socket. That matters: a slow dependency is only
interesting because the caller's thread is held in a read it cannot escape.

### `api-slow-upstream`

**In production.** A vendor that normally answers in 50ms starts taking two seconds, while your
traffic is unchanged.

**What to watch.** Thread count climbs towards the container maximum and flatlines there. CPU
stays low, because the threads are waiting rather than working.

**What to listen for.** The breath pad rises steadily. Very little else.

**Measured**: threads 54 → 125 stuck, thread count to 137, allocation 4 MB/s.

### `api-retry-storm`

**In production.** The upstream starts failing, the client retries three times with no backoff,
and the dependency that was already struggling receives four times the load.

**What to watch.** Stuck threads and allocation step up in multiples.

**What to listen for.** The breath pad rises in steps while the hi-hat quickens.

### `api-no-timeout`

**In production.** An HTTP client built without a read timeout, talking to something that accepts
the connection and never answers.

**What to watch.** Threads enter the wait and never come back. The thread lane only grows.

**What to listen for.** The breath pad rises and stays. Nothing recovers.

**Measured**: 40 requests in 10 seconds (1.6/s), p50 25.06s — exactly the upstream's hold.
Stuck threads 24 → 90.

**Run this one last.** It holds request threads for the full 25 seconds, so anything after it
measures a saturated server. `scenarios.sh all` already orders it last for this reason.

### `api-huge-payload`

**In production.** Somebody added a field and the response went from kilobytes to megabytes,
parsed on every request.

**What to watch.** The most violent of all of them: heap to 503 MB, allocation 4000 MB/s, **1322
collections** in 12 seconds, and a **125ms pause** — the only scenario that trips the long-GC
alarm.

**What to listen for.** Hi-hat racing, heavy kicks, and the alarm firing. This is the loudest.

---

## Redis and caching

### `cache-hit-path`

**In production.** The system working properly. Almost everything served from Redis, the database
barely touched.

**Why it is in the list.** You cannot recognise an unhealthy system without hearing a healthy
one. Play this first and last.

**What to listen for.** A calm drone with the pentatonic melody running. This is what healthy
sounds like.

### `cache-stampede`

**In production.** A hot key expires and every in-flight request recomputes it at once. The
recompute is guarded by a `synchronized` block — which is the usual fix for stampeding the
database, and turns the stampede on the application instead.

**What to watch.** A wall of contention, threads blocked on one monitor for seconds.

**What to listen for.** A dissonant cluster, and the healthy melody cutting out entirely.

**Measured**: 39 contended threads, individual waits to 66ms, heap to 370 MB.

**The lesson.** Double-checked locking does not help here. Everyone has already missed the cache
before anyone acquires the lock.

### `cache-cold`

**In production.** A restart or a flush leaves the cache empty, so every request falls through to
the database until it refills.

**What to watch.** Sustained elevated allocation and pool pressure, slowly subsiding. **The
recovery is the interesting part** — most scenarios only get worse.

**Measured**: allocation 520 MB/s, 148 collections, 85 stuck threads, easing as hit rate climbs.

### `cache-unbounded-local`

**In production.** A `HashMap` used as a cache, keyed by something unbounded such as a session or
a request id, with no eviction and no size limit.

**What to watch.** Heap climbs steadily and never returns. Collections run more often and reclaim
less each time.

**What to listen for.** The drone climbs and the leak swell fires repeatedly.

**Measured**: heap 26 → 417 MB.

---

## Concurrency model

### `conc-thread-per-request` · `conc-block-event-loop` · `conc-offload-elastic`

These three are one experiment. Run it with `compare`, which drives identical external load at
both servers:

```bash
./sonify-demo/scripts/scenarios.sh compare conc-thread-per-request 60 20
```

**Measured**, 60 users, identical work:

| | throughput | p50 | p99 |
| --- | --- | --- | --- |
| Tomcat, thread per request | 191.6 req/s | 309ms | 394ms |
| Netty, `block()` on the event loop | **24.9 req/s** | 2200ms | 4466ms |
| Netty, offloaded to `boundedElastic` | 189.5 req/s | 308ms | 399ms |

**Why 24.9.** Eight cores means eight event loops. Each serves one 300ms call at a time, so
8 ÷ 0.3s ≈ 26 requests a second. Throughput was not degraded by load — it was **pinned to the
number of event loops**.

**What makes it frightening.** The thread count stays perfectly flat. Nothing grows, no pool
reports saturation, every resource graph looks healthy. Near silence from the monitor, and the
absence is the symptom.

**The fix, audible.** The third row is the same blocking work moved to `boundedElastic`. Event
loops stay free, throughput matches Tomcat exactly, and the melody keeps playing.

### `conc-lock-convoy`

**In production.** A `synchronized` method on a shared object in the request path. Fine at low
traffic, a queue the moment concurrency rises.

**What to watch.** `BLOCKED` climbing while `WAITING` stays flat — the mirror image of pool
exhaustion, which is `WAITING` climbing with `BLOCKED` at zero. Same symptom to a user,
completely different fix.

**What to listen for.** A thick dissonant cluster for as long as the load lasts.

**Measured** (50 users, in-process): p50 **39µs**, p95 40µs, p99 42µs, max **4.26 seconds**.

**The lesson in that one line.** Intrinsic locks are not fair. The typical acquisition takes 39
microseconds while the worst waits over four seconds, because threads that just released the lock
reacquire it before a queued thread is scheduled. A p50 or even a p99 would tell you nothing was
wrong. Only the maximum shows the starvation.

---

## The signature table

Measured at 40 users for 12 seconds each. `db-n-plus-one` was recalibrated after this sweep and
now produces a considerably stronger signature than shown.

| scenario | heap MB | alloc MB/s | cpu | thr | blocked | stuck | gc | max pause |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| db-pool-exhaustion | 29–55 | 5 | 22% | 77 | 0 | 85 | 2 | 5.5ms |
| db-n-plus-one | 27–59 | 526 | 39% | 77 | 0 | — | 346 | 9.7ms |
| db-unbounded-result | 27–**440** | **4025** | 66% | 76 | 0 | — | **785** | 6.8ms |
| db-connection-leak | 27–37 | 1 | 11% | 76 | 0 | **65** | 2 | 2.0ms |
| api-slow-upstream | 27–49 | 4 | 36% | 118 | 0 | **125** | 2 | 2.0ms |
| api-retry-storm | 29–67 | 7 | 21% | 124 | 0 | — | 4 | 2.3ms |
| api-no-timeout | 29–36 | 2 | 13% | **137** | 0 | **90** | 2 | 3.1ms |
| api-huge-payload | 29–**503** | **4000** | **78%** | 130 | 1 | — | **1322** | **125.3ms** |
| cache-hit-path | — | 518 | 63% | 131 | 0 | — | 37 | 6.9ms |
| cache-stampede | 29–370 | 499 | 37% | 130 | **39** | — | 102 | 3.0ms |
| cache-cold | 25–83 | 520 | 57% | 119 | 0 | — | 148 | 3.6ms |
| cache-unbounded-local | 26–**417** | 277 | 58% | 76 | 0 | — | 67 | 9.2ms |
| conc-thread-per-request | 33–79 | 4 | 15% | 111 | 0 | — | 2 | 2.4ms |
| conc-lock-convoy | 34–44 | 1 | 21% | 111 | **40** | flat | 2 | 3.2ms |

---

## Picking six for a reel

A good sequence needs contrast, not just drama.

- **Open calm** with `cache-hit-path`, so there is a healthy baseline to depart from.
- **Loud and visual**: `api-huge-payload` (the only long-GC alarm), `db-unbounded-result`,
  `cache-stampede`, `conc-lock-convoy`.
- **Quiet and shocking**: `conc-block-event-loop` and `db-pool-exhaustion`, where the point is
  how little happens while the application dies.

The quiet ones are the most educational and the least impressive on their own. They work when
they follow something loud.
