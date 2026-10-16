# Architecture

How a garbage collection in your JVM becomes a kick drum in a browser, and why each piece is
where it is.

---

## The path

```
┌──────────────────────────── your JVM ────────────────────────────┐
│                                                                  │
│   JFR RecordingStream              platform MXBeans              │
│   (in process, no files)           (exact, instantaneous)        │
│                                                                  │
│    GarbageCollection                MemoryMXBean                 │
│    GCHeapSummary                      heap used / committed / max│
│    JavaMonitorEnter                 ThreadMXBean                 │
│    ThreadPark                         count, BLOCKED, WAITING    │
│    SocketRead                         per-thread allocation      │
│         │                           OperatingSystemMXBean        │
│         │                             process and machine CPU    │
│         │                                    │                   │
│    MetricsAccumulator                 JvmVitals                  │
│    (lock free, JFR thread)            AllocationSampler          │
│         │                                    │                   │
│         └──────────────┬─────────────────────┘                   │
│                        ▼                                         │
│                  TickAssembler            every sonify.tick      │
│                  AllocationRateMeter      (default 100ms)        │
│                        │                                         │
│                        ▼                                         │
│                  SonifyBroker ──────► discrete gc / contention   │
│                        │               published immediately     │
│                  ┌─────┴─────┐                                   │
│               EventBuffer  EventBuffer     one per client,       │
│               (drop oldest) (drop oldest)  never blocks          │
└──────────────────────┼─────────────────────┼────────────────────┘
                       ▼                     ▼
              SonifyMvcController   SonifyWebFluxController
              SseEmitter            Flux<ServerSentEvent>
                       └──────────┬──────────┘
                                  ▼
                         GET {base-path}/stream
                                  ▼
                    ┌─────────────────────────┐
                    │   browser (one page)    │
                    │                         │
                    │  sonify.js  state, SSE  │
                    │  viz.js     Canvas 2D   │
                    │  audio.js   Web Audio   │
                    └─────────────────────────┘
```

---

## Two sources, and the rule for choosing

The single most important design decision: **JFR is used only for what nothing else can see.**

| Reading | Source | Why |
| --- | --- | --- |
| heap used, committed, max | `MemoryMXBean` | continuous; `GCHeapSummary` only fires at a collection |
| CPU, process and machine | `OperatingSystemMXBean` | `jdk.CPULoad` reports zero on macOS |
| thread count | `ThreadMXBean` | exact, no event needed |
| threads BLOCKED / WAITING | `ThreadMXBean` | **instantaneous**; JFR reports a wait only when it ends |
| allocation, total and per-thread | `ThreadMXBean` | JFR's sampler measured 55% low |
| collection pauses and causes | **JFR** | nothing else reports them |
| monitor contention | **JFR** | nothing else names the thread and the monitor |
| time blocked in socket reads | **JFR** | nothing else reports it |

Every one of those choices is a measurement, written up in [FINDINGS.md](FINDINGS.md).

A useful consequence: if Flight Recorder is unavailable, heap, CPU, threads and allocation keep
working. Sonify logs one warning and runs with fewer vitals rather than failing.

---

## Modules

```
jvm-sonify
├── sonify-core                        the engine. Pure Java, ZERO dependencies.
├── sonify-spring-boot-autoconfigure   wiring, HTTP transports, the browser UI
├── sonify-spring-boot-starter         thin pom, the single dependency you add
├── sonify-scenarios                   real production pathologies, transport agnostic
├── sonify-demo                        Tomcat application, port 8080
└── sonify-demo-reactive               Netty application, port 8081
```

The split exists so the library never depends on the demo, and so the same scenario code can be
driven from a servlet application and a reactive one and compared directly.

---

## sonify-core

Zero dependencies, deliberately. It logs through JDK `System.Logger` and writes its own JSON, so
it can be used without Spring and cannot be broken by a dependency upgrade. That choice paid off
when Spring Boot 4 moved from Jackson 2 to Jackson 3: the wire format was untouched.

### The event model

A sealed interface with three records, in `net.bitsar.sonify.core.event`:

- **`TickEvent`** — a periodic snapshot of every vital. `cpu` and `machineCpu` are boxed `Double`
  so a JVM that cannot report them sends `null` rather than a convincing zero.
- **`GcEvent`** — one collection. `pauseMs` is the summed stop-the-world time, which is what the
  application actually felt; `durationMs` is the wall-clock span and is larger for a concurrent
  collector. `reclaimedBytes` is the drop across the collection, which is how a leak announces
  itself: a large heap reclaiming almost nothing.
- **`ContentionEvent`** — a thread blocked on a monitor beyond the threshold.
- **`ThreadSlice`** — what one thread was doing in a tick, used for the per-thread lane.

### Collection

- **`JfrEventPump`** owns the single `RecordingStream` for the whole JVM. One stream serves every
  connected browser. Stack traces are switched off on every subscription, because nothing reads
  them and capturing them is the expensive part of JFR. Each handler swallows its own failures;
  nothing here may disturb the application being measured.
- **`MetricsAccumulator`** is written only from the JFR thread, lock free, and drained by the tick
  thread. Tracked threads are capped so thread churn cannot grow it without bound.
- **`JvmVitals`** reads heap, threads, CPU and thread states. CPU readings are held briefly across
  gaps, because the OS recomputes load on its own schedule and answers −1 if asked again too soon;
  without the hold the reading flickers between a value and nothing.
- **`AllocationSampler`** reads exact allocation counters, preferring Java 21's JVM-wide total
  through a `MethodHandle` and falling back to summing live threads on Java 17.

### Assembly and fan-out

- **`AllocationRateMeter`** turns a monotonic counter into MB/s over a trailing window, and stays
  silent until it has a full one.
- **`TickAssembler`** merges everything into a `TickEvent` and ranks the threads worth showing:
  contended first, then waiting on the network, then the heaviest allocators.
- **`SonifyBroker`** is the public entry point. Nothing is measured until the first subscriber;
  the pipeline stops a grace period after the last one leaves. An application nobody is watching
  pays nothing at all.
- **`EventBuffer`** is a bounded queue per client that drops its oldest entry when full. A slow
  browser loses old events; it never slows the JVM being measured.

---

## sonify-spring-boot-autoconfigure

`SonifyAutoConfiguration` is gated on `sonify.enabled=true`, so the starter is inert on the
classpath. Transport is chosen by `@ConditionalOnWebApplication`, so a servlet application gets
`SonifyMvcController` returning an `SseEmitter`, and a reactive one gets
`SonifyWebFluxController` returning a `Flux<ServerSentEvent<String>>`. Both are mapped at
`${sonify.base-path}/stream` and send pre-serialised JSON as text, so no message converter can
re-quote it.

`SonifyUiController` serves the page from a fixed allowlist of five filenames, rather than
relying on Boot's default static locations. That makes `sonify.base-path` genuinely move the UI,
and means no other classpath resource can be fetched through it.

`SonifySecurityAdvisor` **changes no security configuration**, deliberately. The stream exposes
thread names, monitor classes and heap figures; whether that should be reachable is the
application's decision. It logs once at startup saying which situation you are in.

The Actuator endpoint is read-only status. The stream stays a plain controller because Actuator's
web endpoints cannot stream server-sent events cleanly.

---

## The browser

Three files, vanilla JavaScript, no framework and no build step.

- **`sonify.js`** holds the rolling state, the SSE connection with backoff, the event log, clip
  recording, and the measure of how many threads are stuck.
- **`viz.js`** draws the timeline. Everything is placed by the event's own timestamp rather than
  arrival order, because JFR delivers in batches; a collection from 800ms ago belongs 800ms left
  of the playhead. Allocation uses a log axis, since the measured range runs from idle to tens of
  gigabytes a second. Heap is scaled to committed rather than max, or a lightly used large heap
  presses the line flat against the floor and hides the sawtooth.
- **`audio.js`** is the engine, described in [SOUND-DESIGN.md](SOUND-DESIGN.md).

### Why "waiting" is computed in the browser

An idle application still has parked threads: pool workers with nothing to do park exactly like a
request waiting for a connection. What distinguishes trouble is the *rise* above that resting
level, so the page keeps a rolling floor of the lowest count seen recently and only counts the
excess. Threads blocked in a socket read always count, since a thread in a read is never idle.

---

## Threads this creates

| thread | when | what it does |
| --- | --- | --- |
| JFR Event Stream | first subscriber | delivers events; must never block |
| `sonify-tick` | first subscriber | assembles and publishes a tick |
| `sonify-sse-N` | per client (MVC) | drains that client's buffer to the socket |

All daemon threads. All gone once the last client leaves and the grace period elapses.

---

## Wire format

Server-sent events, with the `event:` name carrying the type, and the payload written by hand so
the format is pinned by unit tests rather than by a mapper's defaults.

```json
{"type":"tick","t":1734567890123,"heapUsed":512000000,"heapCommitted":1073741824,
 "heapMax":2147483648,"allocMBps":220.4,"cpu":0.37,"machineCpu":0.5,"threads":42,
 "contended":3,"parked":7,"ioWait":4,"blocked":2,"waiting":9,
 "threadDetail":[{"name":"http-nio-8080-exec-4","state":"contended",
                  "allocBytes":4194304,"blocks":2,"parks":0,"io":0}]}

{"type":"gc","t":1734567890456,"name":"G1 Young Generation","cause":"G1 Evacuation Pause",
 "pauseMs":12.7,"durationMs":12.7,"heapUsedAfter":25000000,
 "heapCommittedAfter":110000000,"reclaimedBytes":62000000}

{"type":"contention","t":1734567890789,"thread":"http-nio-8080-exec-4",
 "monitor":"example.Cache","durationMs":48.2}
```

`parked` and `ioWait` are event-driven counts of distinct threads. `blocked` and `waiting` are
instantaneous thread-state counts, and are the readings that stay truthful when nothing ever
recovers. Both are sent because they mean different things.
