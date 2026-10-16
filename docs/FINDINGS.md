# Findings

Everything here was measured on this project, and almost all of it contradicted an assumption
that seemed safe at the time. The numbers are from an 8-core Apple Silicon machine running
Temurin 21.0.3 on macOS; where a result is platform-specific that is said explicitly.

This is the document to read if you want to learn something. The rest describe what the code
does; this one explains why it does it that way, and what the obvious approach got wrong.

---

## 1. JFR reports zero CPU for the JVM on macOS

**The assumption.** `jdk.CPULoad` gives you `jvmUser`, `jvmSystem` and `machineTotal`, so JFR can
be the single source for CPU.

**The measurement.** Four threads burning CPU on eight cores, for six seconds:

```
jdk.CPULoad   jvmUser=0.000  jvmSystem=0.000  machineTotal=0.240
OperatingSystemMXBean   processCpuLoad=0.497   systemCpuLoad=0.683
```

The MXBean reports a correct 0.497 — four threads of eight is 50%. JFR reports the JVM using
*nothing* while correctly seeing the machine at 24%.

**The consequence.** CPU comes from `com.sun.management.OperatingSystemMXBean`. Had this gone
unnoticed, the CPU lane would have read a flat zero on the machine the demo was built on.

**The lesson.** A field existing in an API does not mean it is populated on your platform. If a
reading matters, cross-check it against a second source before you build on it.

---

## 2. Flight Recorder events arrive in batches, and you cannot tune it away

**The assumption.** A `RecordingStream` is a live stream, so events arrive as they happen.

**The measurement.** Delivery latency, measured as `now - event.getStartTime()`:

| flush interval | median | p90 | max |
| --- | --- | --- | --- |
| default | 548ms | 954ms | 1011ms |
| `flush-interval` = 100ms | 448ms | 864ms | 900ms |
| `flush-interval` = 200ms | 598ms | 981ms | 1005ms |

Setting the documented `flush-interval` through `setSettings` changed nothing outside noise.

**The consequence.** Everything JFR sources — collection pauses, contention, parking — reaches
the browser up to a second after it happened. That is inherent, not a bug to fix, and the README
says so rather than claiming "live".

**The lesson.** Measure latency before you promise it. And when a knob does not move the number,
say the knob does not work rather than assuming you turned it wrong.

---

## 3. Batched events share a timestamp unless you ask for theirs

**The symptom.** A 14-second run produced 780 collections, and dozens of them carried the
*identical* millisecond timestamp.

**The cause.** Events were stamped with `System.currentTimeMillis()` as they were handled. Since
JFR delivers a second of events in one burst, they all got the moment of delivery.

**The fix.** `event.getStartTime()` carries when the event actually occurred.

```
before: 780 events,  a handful of distinct timestamps, dozens per millisecond
after:  780 events, 780 distinct timestamps, busiest millisecond holds 1
```

**Why it mattered more than it looks.** Sonified, the first version fires forty kick drums
simultaneously and then goes silent for a second. On the timeline, a second of history piles into
one vertical line. The data was always correct; it was being *placed* wrong.

---

## 4. JFR's allocation sampling was 55% wrong

**The assumption.** Summing `weight` across `jdk.ObjectAllocationSample` gives total allocation.
It is throttled and extrapolated, but should be roughly right.

**The measurement.** Allocate a precisely known 125 MiB and ask both sources:

| source | reported | error |
| --- | --- | --- |
| known truth | 131,072,000 bytes | — |
| thread bean counters | 131,258,688 bytes | **+0.1%** |
| JFR summed `weight` | 58,843,656 bytes | **−55.1%** |

**The consequence.** Allocation comes from `com.sun.management.ThreadMXBean`. This also deleted
the highest-volume JFR subscription, so accuracy and overhead improved together.

**The lesson.** A statistical sampler at 150 samples a second is not a measurement. When an exact
counter exists, prefer it — and when you have both, calibrate one against the other.

---

## 5. A rate measured over one tick multiplies a batch by ten

**The symptom.** Allocation peaked at **469,218 MB/s**. That is 469 GB/s, which no machine does.

**The cause.** Two errors compounding. JFR delivered a second of samples into one 100ms tick, and
dividing by 100ms extrapolated it tenfold — on top of the 55% sampling error, in the other
direction.

**The fix.** `AllocationRateMeter` averages a monotonic counter over a window spanning several
batches, and reports nothing until it has a full window, because a partial window *is* the short
span that produces the silly number.

```
469,218 MB/s   per-tick rate over JFR samples
 47,351 MB/s   windowed rate over JFR samples
     43 GB/s   windowed rate over exact counters  (real: corroborated below)
```

**Is 43 GB/s real?** Yes, and it is worth seeing why. G1 did 703 young collections in 5 seconds,
each reclaiming about 250MB. 703 × 250MB over 5s is roughly 35 GB/s, which corroborates it
independently. Apple Silicon really does allocate short-lived arrays that fast.

**The lesson.** When a number is absurd, find a second way to derive it. Two independent
derivations agreeing is the only thing that makes a surprising number believable.

---

## 6. Polling per-thread counters cannot see a thread that lived briefly

**The symptom.** After switching to exact counters, allocation still read zero under load, while
*per-thread* figures were perfect. The same data source disagreed with itself.

**The cause.** A thread that allocates 150MB and exits does so in about 15 milliseconds. A
sampler that polls every 100ms never observes it alive, so its allocation is invisible. This is
not an edge case: it is exactly what a request handler does.

**The measurement.** A burst thread allocating 150 MiB and exiting, watched by both methods:

```
tick 16   cumulative jumped +157,436,112 bytes   per-thread sum saw 12,248 bytes
```

The exact counter caught all 150 MiB. The sum of live threads caught 12 KB of unrelated noise.

**The fix.** `getTotalThreadAllocatedBytes()`, which the JVM maintains internally and which
therefore includes threads that have died. It is Java 21+, so it is called through a
`MethodHandle` and falls back to summing live threads on 17, with the limitation documented.

**The lesson.** Sampling sees what is alive when you look. Anything with a lifetime shorter than
your sampling interval is invisible, and in a web application that is most of the work.

---

## 7. JFR tells you about a wait only once it has ended

This was the most consequential finding, and the least obvious.

**The symptom.** A scenario that deliberately leaked every connection in the pool, with thirty
threads stuck waiting, reported **five parked threads**. An application with no read timeout
talking to a dead upstream reported the same five.

**The cause.** JFR duration events carry a duration, so they are emitted when the wait *finishes*.
A thread that is stuck right now has emitted nothing. For the failure modes that matter most —
where nothing ever recovers — every thread is stuck and therefore every thread is silent.

An event-driven view of a completely stalled application shows an idle application.

**The fix.** Ask `ThreadMXBean` directly, every tick, how many threads are `BLOCKED` or
`WAITING` at this instant. It costs about 44 microseconds and does not care whether anyone is
ever going to get unstuck.

| scenario | resting | before (park events) | after (thread states) |
| --- | --- | --- | --- |
| connection leak | 25 | 5 | **65** |
| missing read timeout | 24 | 5 | **90** |
| slow upstream | 54 | 85 | **125** |

**The bonus.** The two states separate pathologies that previously looked identical:

| scenario | `BLOCKED` | `WAITING` |
| --- | --- | --- |
| lock convoy | **39** | flat |
| pool exhaustion | 0 | **+31** |

A lock convoy is threads queueing for a monitor; pool exhaustion is threads parked on a future.
Same symptom to a user, completely different fix, and now visibly different.

**The lesson.** Event streams describe things that *happened*. If you need to know what *is*, you
need a different kind of reading. Most monitoring failures of stalled systems are this mistake.

---

## 8. A threshold tuned for one wait is wrong for another

**The symptom.** `jdk.SocketRead` at a 20ms threshold reported nothing at all, even while an N+1
query storm was obviously hammering the database.

**The cause.** A query against a local database returns in well under a millisecond. What makes
an N+1 slow is the *number* of reads, not the length of any one. A 20ms floor excludes every
single one of them.

**The fix.** 5ms. Low enough to catch a genuinely slow read, high enough to ignore the ordinary
fast ones.

**The second cause, which the threshold did not fix.** `java.net.http.HttpClient` is NIO-based: a
selector thread does non-blocking reads while the calling thread parks on a future. It emits no
`SocketRead` events at all, however low the threshold. Blocking clients — JDBC drivers,
`HttpURLConnection`, Apache HttpClient — do emit them.

**The lesson.** Know which of your clients block. "Waiting on I/O" is not one signal; it is at
least two, and which one you see depends on a library's implementation rather than on your code.

---

## 9. Blocking an event loop does not degrade throughput, it caps it

**The measurement.** Identical work, 60 concurrent users, driven over real HTTP from a separate
process:

| | throughput | p50 | p99 |
| --- | --- | --- | --- |
| Tomcat, thread per request | 191.6 req/s | 309ms | 394ms |
| Netty, `block()` on the event loop | **24.9 req/s** | 2200ms | 4466ms |
| Netty, offloaded to `boundedElastic` | 189.5 req/s | 308ms | 399ms |

**Why 24.9 and not some other bad number.** The machine has 8 cores, so Netty runs 8 event loops.
Each can serve one 300ms call at a time: 8 ÷ 0.3s ≈ 26 requests a second. Throughput did not
degrade under load, it was *pinned* to the number of event loops.

**Why it is hard to spot in production.** The thread count stays perfectly flat. Nothing grows,
nothing is exhausted, no pool reports saturation. Every resource graph looks healthy while
throughput sits at a twentieth of what it should be.

**The lesson.** On a thread-per-request server, blocking costs you a thread. On an event loop it
costs you a fraction of the entire server's capacity, and the usual dashboards cannot see it.

---

## 10. Driving load from inside the process measures the wrong thing

**The mistake.** The first scenario runner spawned threads inside the application. Convenient, no
external tooling.

**Why it is wrong.** Those threads are not Tomcat worker threads and not Netty event loops. The
server's concurrency model is never exercised, so the comparison above is impossible to make. The
in-process driver reported 25,424 req/s for a lock convoy; over HTTP the same scenario is bounded
by the server long before the lock matters.

**The lesson.** If the thing under test is the server, the load has to arrive the way real load
arrives. Both drivers are kept, and the documentation says which to use for what.

---

## 11. Measuring small overhead needs a paired experiment, and knowing when to stop

Three attempts, the first two of which produced impossible results.

**Attempt 1.** Two JVMs, one with the feature disabled, load driven by a shell loop. Reported
that enabling monitoring made the application **12% faster**. The loop measured the cost of
spawning `curl`, and the two JVMs differed in JIT and heap state as much as in the thing
under test.

**Attempt 2.** One JVM, toggling only whether a client was connected. Reported **−7.4% CPU**.
The unwatched condition always ran first in each pair and absorbed the drift.

**Attempt 3.** One JVM, ABBA ordering so neither condition holds the favourable position,
workload executed inside the JVM on one request so no shell cost is counted:

```
median wall time :   1532ms ->   1512ms   -1.2%
median cpu time  :   2066ms ->   2100ms   +1.7%
run-to-run spread within the unwatched condition alone: 4.8%
```

**The conclusion, stated honestly.** The overhead is **below what the harness can resolve**. The
measured +1.7% sits inside the 4.8% spread of the baseline against itself. The fair claim is "a
low single-digit percentage at most", not "1.7%".

**The lesson.** A benchmark that reports an impossible result is telling you about your harness.
And when the effect is smaller than your noise, the honest output is a bound, not a number.

---

## 12. Things that cost nothing, measured

Worth knowing, because several design choices here rest on them:

| operation | cost | at a 100ms tick |
| --- | --- | --- |
| heap + thread count + CPU load | 1.4µs | 0.0014% |
| all thread ids + per-thread allocation + total | 16.6µs | 0.017% |
| `getThreadInfo(allIds, 0)` for thread states | 43.8µs | 0.044% |
| starting a `RecordingStream` | 13ms | once, on first subscriber |

MXBean reads are far cheaper than their reputation. Resolving thread *names* is 25× the cost of
reading their counters, which is why names are cached and counters are not.

---

## 13. Spring Boot 4 moved off Jackson 2

`com.fasterxml.jackson.databind` does not resolve on Boot 4.1.1; it uses Jackson 3 under
`tools.jackson`. This broke a test helper and never touched the library, because the wire format
is written by hand in `sonify-core` rather than delegated to a mapper.

That decision was made to keep the core module dependency-free. It paid for itself somewhere
entirely different, which is the usual way with dependency decisions.

---

## 14. Three build traps that cost real time

**`-parameters`.** Spring binds `@PathVariable` and `@RequestParam` by parameter name. Without
the flag the names are erased and every such request fails at runtime with a 500.
`spring-boot-starter-parent` sets it; a build that only imports the BOM does not. Library code
should name its bindings explicitly so it never depends on a consumer's compiler flags.

**A green build that throws compilation errors at runtime.** `java.lang.Error: Unresolved
compilation problem` is the *Eclipse* compiler's error stub. An IDE had written it into
`target/classes`; Maven saw a class newer than its source, skipped recompiling, and packaged the
stub. `mvn clean` is the fix. A successful build plus runtime compilation errors always means
stale IDE output.

**Rebuilding under a running application.** Spring Boot's nested-jar loader reads classes lazily.
Replacing the jar while the application runs produces `ClassNotFoundException` for classes that
are plainly present. Stop it, build, start it.

---

## 15. `--release 17` earns its keep

Compiling on JDK 21 with `--release 17` turned a latent production bug into a compile error:
`getTotalThreadAllocatedBytes()` exists on 21 and not on 17. A probe written without the flag
worked perfectly and would have shipped a library that failed on its own stated baseline.

Setting the source level is not the same as compiling against that platform's API.

---

## 16. One CSS trap, for completeness

Level meters reported `width: 100%` in the DOM and rendered at **0 pixels**. Both the bar and its
fill were `<span>` elements, and inline elements ignore `width` and `height`. The outer bar looked
right only because grid items are blockified; its child was not a grid item.

Comparing the style value against `getBoundingClientRect()` is the fastest way to catch a layout
fault that reads as correct in the DOM.

---

## 17. The obvious fix for an N+1 is far worse than the N+1

**The assumption.** An N+1 is fixed with a fetch join. `@EntityGraph(attributePaths = "lines")`
on a paginated query gives you the page in two queries instead of a hundred and one.

**The measurement.** Three implementations of one listing page, all returning identical results,
driven at 20 users for 10 seconds:

| strategy | single call | under load |
| --- | --- | --- |
| two queries, by identifier | **33ms** | **1936 req/s**, p50 8ms |
| lazy association (the N+1) | 71ms | 337 req/s, p50 58ms |
| fetch join with pagination | 585ms | **~0 req/s**, p50 35s |

**Why.** A collection join multiplies rows, so the database cannot apply `LIMIT` without losing
lines. Hibernate therefore fetches *every* matching row and paginates in memory. Asking for a
hundred orders loaded forty thousand orders and two hundred thousand lines.

**The fix that works** is two queries: one for the page of identifiers, which the database can
limit because nothing is joined, and one for exactly those orders with their lines.

**The lesson.** The fix looked more correct than the bug and was hundreds of times slower. It was
only caught by running it under load — a unit test asserting the right rows come back passes for
all three.

---

## 18. Spring has four discovery mechanisms, and component scanning is only one

Startup failed with `No qualifying bean of type OrderRepository`, in a module whose services were
being found correctly by `@ComponentScan`.

| What | Found by | Defaults to |
| --- | --- | --- |
| `@Component`, `@Service`, `@RestController` | `@ComponentScan` | the application's package |
| `@Entity` | **`@EntityScan`** | the application's package |
| `Repository` interfaces | **`@EnableJpaRepositories`** | the application's package |
| library configuration | **`AutoConfiguration.imports`** | package-independent |

Component scanning found the services and nothing else, because entities and repositories are
discovered separately. All three of the scanning mechanisms default to the main application's own
package, so any code in a different package tree needs each one declared.

Only the fourth is package-independent, which is exactly why libraries use it: a consumer of a
properly built starter needs no annotations at all. A library that requires
`@ComponentScan("com.vendor.thing")` forces every consumer to know its internal package names.

---

## What generalises

1. **Cross-check any reading you intend to build on.** Items 1, 4 and 5 were all caught by
   deriving the same number a second way.
2. **Know whether a signal is event-driven or state-driven.** Item 7 is the big one: event streams
   describe what happened, and a stalled system is defined by things *not* happening.
3. **Sampling misses anything shorter than its interval**, and in a web application that is most
   of the work.
4. **An impossible benchmark result is about your harness**, and an effect smaller than your noise
   deserves a bound rather than a number.
5. **A fix is not a fix until it is measured under load.** Item 17 looked more correct than the
   bug and was hundreds of times slower.
6. **Say what you did not verify.** Cross-platform behaviour, the sound design and real production
   load are all untested here, and the documentation says so.
