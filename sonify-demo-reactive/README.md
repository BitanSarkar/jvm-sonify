# sonify-demo-reactive

The same scenarios on Netty rather than Tomcat, port 8081. Run both at once, each with its own
Sonify page, and drive identical load at them.

```bash
java -Xmx512m -jar sonify-demo-reactive/target/sonify-demo-reactive-0.1.0-SNAPSHOT.jar
```

```bash
./sonify-demo/scripts/scenarios.sh compare conc-thread-per-request 60 20
```

## The point of this module

Every scenario here is blocking work, because the dependencies are blocking: JDBC and a
synchronous HTTP client. There are only two honest things to do with blocking work on an event
loop, and both are offered so they can be heard against each other.

- **`conc-block-event-loop`** runs it straight on the event loop. This is the mistake.
- **Everything else** is offloaded to `boundedElastic`. This is the correct handling.

## Measured

60 users, identical work, 8-core machine:

| | throughput | p50 | p99 |
| --- | --- | --- | --- |
| Tomcat, thread per request | 191.6 req/s | 309ms | 394ms |
| Netty, `block()` on the event loop | **24.9 req/s** | 2200ms | 4466ms |
| Netty, offloaded correctly | 189.5 req/s | 308ms | 399ms |

Eight cores means eight event loops; each serves one 300ms call at a time, so 8 ÷ 0.3s ≈ 26 a
second. Throughput was not degraded by load, it was **pinned to the number of event loops**.

The thread count stays perfectly flat throughout. Nothing grows to show the damage, which is what
makes this failure so hard to recognise from a dashboard.
