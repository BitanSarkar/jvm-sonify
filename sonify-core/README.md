# sonify-core

The engine. Pure Java, **zero dependencies**, no Spring.

It logs through JDK `System.Logger` and writes its own JSON, so it can be used without Spring and
cannot be broken by a dependency upgrade. That paid off when Spring Boot 4 moved from Jackson 2
to Jackson 3: the wire format was untouched.

## Using it directly

```java
SonifyBroker broker = new SonifyBroker(SonifyConfig.defaults());
try (Subscription subscription = broker.subscribe()) {
    SonifyEvent event = subscription.poll(Duration.ofSeconds(1));
    System.out.println(SonifyEventJson.toJson(event));
}
```

Nothing is measured until the first `subscribe()`, and the pipeline stops a grace period after
the last subscription closes.

## What lives here

| Package | Contents |
| --- | --- |
| `event` | `SonifyEvent` sealed interface, `TickEvent`, `GcEvent`, `ContentionEvent`, `ThreadSlice` |
| `json` | `SonifyEventJson`, a hand-written writer that pins the wire format |
| `jfr` | `JfrEventPump` owning the single `RecordingStream`, plus name formatters |
| `metrics` | `JvmVitals`, `AllocationSampler`, `AllocationRateMeter`, `MetricsAccumulator`, `TickAssembler` |
| (root) | `SonifyBroker`, `Subscription`, `EventBuffer`, `SonifyConfig`, `SonifyStatus` |

## Design rules

- **JFR only for what nothing else can see**: collection pauses, monitor contention, socket reads.
  Everything measurable exactly comes from platform MXBeans.
- **Never disturb the application being measured.** Handlers swallow their own failures, buffers
  drop their oldest entry rather than blocking, and a JFR failure logs one warning and degrades.
- **Null, never a convincing zero.** A reading the platform cannot supply is sent as `null`.

## Seeing it without a browser

```bash
mvn -pl sonify-core test-compile
java -Xmx512m -cp sonify-core/target/classes:sonify-core/target/test-classes \
     net.bitsar.sonify.core.dev.DevMain
```

`DevMain` drives itself through calm, allocation spike, contention storm, leak and recovery, and
prints the JSON that would go down the wire.

See [ARCHITECTURE.md](../docs/ARCHITECTURE.md) and [FINDINGS.md](../docs/FINDINGS.md).
