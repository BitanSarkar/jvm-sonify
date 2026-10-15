# JVM Sonify

**Hear your Spring Boot app run.** Add one dependency, set one property, open one URL — and
your JVM becomes a multi-parameter patient monitor. Heap is a drone that rises as memory fills.
Every garbage collection is a kick drum. Allocation sets the hi-hat tempo. Lock contention
arrives as a detuned, dissonant cluster, and a long pause trips a broadband alarm. A healthy
application sounds like a calm heartbeat with a melody over it; a struggling one sounds tense,
and you can tell *which* vital went wrong with your eyes shut, because each one owns its own
timbre.

![JVM Sonify under load](docs/screenshot.png)

Nothing like this exists as a library. `flight-recorder-starter` exposes JFR through Actuator,
but it is record, download, flamegraph. A 2020 tersesystems blog post sonified GC through
MXBeans as a one-off experiment. This is live JFR streaming, browser visuals and Web Audio, as
a zero-code Spring Boot starter.

---

## Quick start

**1. Add the starter**

```xml
<dependency>
  <groupId>net.bitsar</groupId>
  <artifactId>sonify-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**2. Turn it on**

```properties
sonify.enabled=true
```

**3. Open it**

```
http://localhost:8080/sonify/
```

Press **Play sound** — browsers require a gesture before any audio can start. No code changes,
no annotations, no wiring.

---

## How it works

```
  ┌─────────────────────────────────────────────┐
  │                  your JVM                   │
  │                                             │
  │  JFR RecordingStream        platform MXBeans│
  │  (in process, no files)     (exact, cheap)  │
  │    GarbageCollection          heap used     │
  │    GCHeapSummary              heap committed│
  │    JavaMonitorEnter           cpu load      │
  │    ThreadPark                 thread count  │
  │         │                     allocation    │
  │         └──────────┬───────────────┘        │
  │                    ▼                        │
  │            aggregator + tick                │
  │         (100ms snapshot, lock free)         │
  │                    │                        │
  │        bounded ring buffer per client       │
  │        (drop oldest, never blocks)          │
  └────────────────────┼────────────────────────┘
                       ▼
              SSE  /sonify/stream
          MVC: SseEmitter · WebFlux: Flux
                       ▼
            ┌──────────────────────┐
            │       browser        │
            │  Canvas 2D timeline  │
            │  Web Audio engine    │
            │  MediaRecorder clip  │
            └──────────────────────┘
```

The recording stream starts on the **first** subscriber (about 13ms) and stops a grace period
after the last one leaves. One stream serves every connected browser. If a client cannot keep
up, its buffer drops the oldest events rather than blocking the JVM being measured.

### Why some readings come from MXBeans, not JFR

JFR is used only for what nothing else can see. Everything measurable exactly is read from the
platform MXBeans instead, which are JDK specification APIs that behave the same on macOS, Linux
and Windows and cost about 17 microseconds per tick. Three measurements drove that split:

| Finding | Consequence |
| --- | --- |
| `jdk.CPULoad` reports `jvmUser` and `jvmSystem` as **0.000** on macOS while the machine is genuinely at 50% | CPU comes from `OperatingSystemMXBean`, which returned a correct 0.497 under the same load |
| `jdk.GCHeapSummary` only fires at a collection | Heap is sampled every tick, so the line keeps moving when the app is idle |
| `jdk.ObjectAllocationSample` summed **55% low** against a precisely known 125 MiB | Allocation comes from the thread bean's counters, which were within **0.1%** |

A useful side effect: if Flight Recorder is unavailable, heap, CPU, threads and allocation all
keep working. Sonify logs one warning and carries on with fewer vitals rather than failing.

**Latency.** JFR flushes in batches of up to a second, and that is not tunable from a
`RecordingStream` (measured: median ~0.5s, max ~1s). Collections and contention therefore reach
the browser up to a second after they happen. They are drawn and sounded at their *own*
timestamps, so the timeline stays truthful, but the kick drum for a GC lands slightly after the
pause itself. Heap, CPU, threads and allocation have no such delay.

---

## The sound

Each vital owns one timbre in its own register, so they layer instead of masking each other.

| Vital | Source | Sound | Register |
| --- | --- | --- | --- |
| Heap used % | MXBean | continuous sine drone, 90→260 Hz | low, a bed under everything |
| CPU load | MXBean | tremolo rate and filter cutoff **on the drone** | shapes the drone, adds no voice |
| Allocation rate | MXBean | hi-hat tempo, log-scaled | high and short |
| GC pause | JFR | kick, sine sweep 140→40 Hz, louder with longer pauses | sub-bass transient — the heartbeat |
| Long GC (>100ms) | JFR | broadband noise burst sweeping down | alarm, cuts through everything |
| Lock contention | JFR | detuned square, pitch per thread | mid, deliberately dissonant |
| Live threads | MXBean | occasional pentatonic notes | mid-high, pleasant when healthy |
| Heap not recovering | derived | slow dissonant swell | a collection that reclaimed almost nothing |
| Threads stuck | MXBean + JFR | breath pad, filtered noise swelling | low-mid, the sound of waiting |
| Parked threads | JFR | **nothing** — drawn only | a parked thread is usually just idle |

Three deliberate choices. Contention **suppresses** the pentatonic melody, so the pleasant layer
falls away exactly when things go wrong. A `DynamicsCompressor` sits on the master bus, so a
contention storm landing on top of a kick does not clip into mush. And the drone **ducks** by 45%
for about 200ms whenever a collection or a contention event fires, because a continuous bed that
never gets out of the way masks the very events it is meant to sit beneath.

The breath pad is what makes a stalled application audible at all. A pool that has run dry, a
slow third-party API or an event loop held by a blocking call allocate nothing, collect nothing
and contend over nothing. Without a voice for waiting they are simply silent, which is the one
thing a monitor must never be. It responds to the *rise* above the resting level of waiting
threads, so an idle pool does not hum.

**Record clip** captures the canvas and the audio into a single WebM through
`canvas.captureStream()` and a `MediaStreamAudioDestinationNode`. Verified output: 1980×706 at
30fps VP9 video plus an Opus audio track.

---

## Configuration

Everything is optional except `sonify.enabled`.

| Property | Default | Meaning |
| --- | --- | --- |
| `sonify.enabled` | `false` | Master switch. Nothing is registered unless this is true. |
| `sonify.base-path` | `/sonify` | Where the UI and the stream live. |
| `sonify.tick` | `100ms` | How often a snapshot of every vital is published. |
| `sonify.max-clients` | `8` | Further connections get 503 rather than degrading everyone. |
| `sonify.buffer-capacity` | `512` | Events held per client before the oldest are dropped. |
| `sonify.max-thread-slices` | `16` | Threads shown in the per-thread lane. |
| `sonify.grace-period` | `30s` | How long the pipeline runs after the last client leaves. |
| `sonify.events.contention-threshold` | `10ms` | Minimum block before contention is reported. |
| `sonify.events.park-threshold` | `20ms` | Higher than contention: most parking is an idle pool thread. |
| `sonify.events.allocation-window` | `2s` | Window the allocation rate is averaged over. |

With Actuator present, `GET /actuator/sonify` reports status, client count and whether JFR came
up. The stream itself stays a plain controller because Actuator's web endpoints cannot stream
server-sent events cleanly.

---

## Security

**Sonify does not touch your security configuration, and that is deliberate.** The stream
exposes thread names, monitor class names and heap figures. Whether that should be reachable is
your application's decision, not this library's, so nothing is silently permitted. On startup it
logs once saying which situation you are in.

With Spring Security on the classpath your existing rules apply unchanged. To open it
explicitly:

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/sonify/**").permitAll()
        .anyRequest().authenticated());
```

To keep it internal, require a role on `/sonify/**`, or move it behind a management port with
`sonify.base-path`. The UI controller serves a fixed allowlist of five filenames, so no other
classpath resource can be fetched through the base path.

---

## Overhead

Measured with `sonify-demo/scripts/overhead.sh`, on one JVM where the **only** thing that changes
between trials is whether a client is subscribed, in ABBA order so neither condition gets the
favourable position. Workload: 4 GB of allocation plus real monitor contention across 8 threads,
6 trials each.

```
median wall time :   1532ms ->   1512ms   -1.2%
median cpu time  :   2066ms ->   2100ms   +1.7%
run-to-run spread within the unwatched condition alone: 4.8%
```

**Read this honestly: the overhead is below what the harness can resolve.** The measured +1.7%
sits inside the 4.8% spread of the baseline condition against itself. The fair claim is that the
cost is at most a low single-digit percentage while a browser is connected, not that it is
exactly 1.7%.

With nobody connected the cost is **zero**, because the recording stream is not running at all.

Two things keep it cheap: stack traces are switched off on every JFR subscription, since nothing
here reads them and capturing them is the expensive part of JFR; and the highest-volume
subscription, allocation sampling, was removed entirely in favour of the thread bean's counters.

Measured on an 8-core Apple Silicon machine, Temurin 21.0.3, macOS.

---

## Demo

```bash
mvn -q -DskipTests package
java -Xmx512m -jar sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar
```

Open <http://localhost:8080/sonify/>, press **Play sound**, then drive it:

| Endpoint | What it does |
| --- | --- |
| `GET /load/alloc?mb=400` | Allocates garbage fast — GC pressure |
| `GET /load/lock?threads=16&ms=50` | Threads queue for one monitor — contention |
| `GET /load/leak?mb=100` | Retains memory — the heap climbs and reclaims less |
| `GET /load/calm` | Releases everything and stops all load |
| `GET /benchmark?mb=1500` | Fixed deterministic workload, reports its own CPU cost |

`sonify-demo/scripts/story.sh` runs the whole sequence — calm, allocation spike, contention
storm, leak, recovery — with on-screen narration. Press **Record clip** first and you have the
reel.

The reactive path can be run by hand with `mvn -Pwebflux spring-boot:run` in `sonify-demo`.

---

## Real-world scenarios

Sixteen pathologies that genuinely happen to production applications, reproduced against real
PostgreSQL, real Redis and a real third-party API running as its own process. Nothing is
simulated with sleeps: the Hikari pool really runs dry, the N+1 really issues one query per row,
and the upstream really holds the socket open.

```bash
brew services start postgresql@16 && brew services start redis
java -cp sonify-scenarios/target/classes net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090
java -Xmx512m -jar sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar              # Tomcat, :8080
java -Xmx512m -jar sonify-demo-reactive/target/sonify-demo-reactive-0.1.0-SNAPSHOT.jar  # Netty, :8081
```

```bash
./sonify-demo/scripts/scenarios.sh list
./sonify-demo/scripts/scenarios.sh run db-n-plus-one 50 30
./sonify-demo/scripts/scenarios.sh compare conc-thread-per-request 60 20
./sonify-demo/scripts/scenarios.sh all
```

| Category | Scenarios |
| --- | --- |
| Database and pool | pool exhaustion, N+1 queries, unbounded result set, connection leak |
| Third-party API | slow upstream, retry storm, missing read timeout, oversized payload |
| Redis and caching | healthy hit path, cache stampede, cold cache, unbounded local cache |
| Concurrency model | thread-per-request, blocking the event loop, correct offloading, lock convoy |

### Drive load from outside when the server is the subject

`scenarios.sh run` drives a scenario from threads inside the application, which is convenient and
enough for anything whose signature is about the JVM itself. It never touches the Tomcat worker
pool or the Netty event loops, so it cannot show either of them filling up. For those, use
`http` or `compare`, which drive real connections from a separate process.

### What blocking the event loop actually costs

Identical work, 60 concurrent users, measured with the external driver on an 8-core machine:

| | throughput | p50 | p99 |
| --- | --- | --- | --- |
| Tomcat, thread per request | 191.6 req/s | 309ms | 394ms |
| Netty, `block()` on the event loop | **24.9 req/s** | 2200ms | 4466ms |
| Netty, offloaded to `boundedElastic` | 189.5 req/s | 308ms | 399ms |

Throughput did not merely degrade, it collapsed to the number of event loops. Eight loops each
serving one 300ms call at a time is about 26 requests a second, and 24.9 is what was measured.
The thread count stays perfectly flat throughout, which is what makes this failure so hard to
recognise from a dashboard: nothing grows to show the damage.

### Seeing a thread that is stuck

Two readings exist for waiting because Flight Recorder reports a wait only when it *ends*. A
drained connection pool or a client with no read timeout emits nothing at all while every thread
sits stuck, so an event-driven view shows an idle application. The thread bean is asked directly
instead, every tick, for how many threads are BLOCKED or WAITING at this instant.

The difference is not academic. Before this, a connection leak reported five parked threads; now
it reports sixty-five stuck against a resting level of twenty-five. The two states also separate
the pathologies cleanly: a lock convoy shows BLOCKED climbing with waiting flat, while pool
exhaustion shows the opposite.

---

## Compatibility

| | |
| --- | --- |
| Java | 17+ (built with `--release 17`) |
| Spring Boot | **3.5.16** and **4.1.1**, both verified by the full test suite |
| Transports | Spring MVC and WebFlux, chosen by classpath |
| Collectors | G1, Parallel, Serial, ZGC and Shenandoah names are mapped |

Boot 4 moved from Jackson 2 to Jackson 3. Sonify is unaffected, because its wire format is
written by hand in `sonify-core` rather than delegated to a mapper — the module has **zero**
dependencies.

**Tested on macOS only.** Every reading uses a JDK specification API with graceful degradation
rather than anything platform-specific, and unavailable values are sent as `null` instead of a
misleading zero, but Linux and Windows are not yet verified. A CI matrix across the three is the
honest way to earn that claim.

---

## Modules

```
jvm-sonify
├── sonify-core                        JFR streaming, aggregation, event model. No Spring, no dependencies.
├── sonify-spring-boot-autoconfigure   Auto-config, properties, MVC and WebFlux endpoints, the UI
├── sonify-spring-boot-starter         Thin pom: core + autoconfigure
└── sonify-demo                        Demo app, load endpoints, story and overhead scripts
```

```bash
mvn verify              # 82 tests, both transports
mvn -Pboot4 verify      # the same suite against Spring Boot 4.1.1
```

## Wire format

Server-sent events, with the `event:` name carrying the type.

```json
{"type":"tick","t":1734567890123,"heapUsed":512000000,"heapCommitted":1073741824,
 "heapMax":2147483648,"allocMBps":220.4,"cpu":0.37,"machineCpu":0.5,"threads":42,
 "contended":3,"parked":7,"threadDetail":[{"name":"http-nio-8080-exec-4",
 "state":"contended","allocBytes":4194304,"blocks":2,"parks":0}]}
{"type":"gc","t":1734567890456,"name":"G1 Young Generation","cause":"G1 Evacuation Pause",
 "pauseMs":12.7,"durationMs":12.7,"heapUsedAfter":25000000,"heapCommittedAfter":110000000,
 "reclaimedBytes":62000000}
{"type":"contention","t":1734567890789,"thread":"http-nio-8080-exec-4",
 "monitor":"example.Cache","durationMs":48.2}
```

`cpu` and `machineCpu` are `null` when the JVM cannot report them. `pauseMs` is the summed
stop-the-world time, which is what the application actually felt; `durationMs` is the
collection's wall-clock span and is larger for a concurrent collector. `reclaimedBytes` is the
drop across the collection — a large heap reclaiming almost nothing is the leak signature.

## Licence

Not yet chosen.
