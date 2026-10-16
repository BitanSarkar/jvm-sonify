# sonify-scenarios

Sixteen real production pathologies, reproduced on purpose against real infrastructure. Transport
agnostic, so the same scenario runs on a Tomcat application and a Netty one and can be compared.

Full guide: **[docs/SCENARIOS.md](../docs/SCENARIOS.md)**.

## What is real here

Nothing is simulated with sleeps. The Hikari pool really runs dry, the N+1 really issues one
query per row against PostgreSQL, Redis is really Redis, and the third-party API is a separate
process holding a real socket open.

| Dependency | What it is |
| --- | --- |
| PostgreSQL 16 | seeded e-commerce schema: 20k customers, 120k orders, 600k order lines |
| Redis | a real server, used as a real cache |
| upstream | `UpstreamServer`, a JDK `HttpServer` in its own process on port 8090 |

## Two drivers, and the difference matters

`ScenarioRunner` spawns threads **inside** the application. Convenient, enough for anything whose
signature is about the JVM itself, and **blind to the server's concurrency model** — it never
touches a Tomcat worker pool or a Netty event loop.

`LoadDriver` drives real connections **from a separate process**. Use it whenever the server is
the subject.

```bash
java -cp sonify-scenarios/target/classes net.bitsar.sonify.scenarios.LoadDriver \
     http://localhost:8080/scenario/conc-thread-per-request/once 60 20
```

## The upstream

```bash
java -cp sonify-scenarios/target/classes \
     net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090
```

| Path | Behaviour |
| --- | --- |
| `/fast` | answers immediately |
| `/slow?ms=2000` | answers after a delay |
| `/flaky?failPercent=70` | fails a configurable share of the time |
| `/huge?mb=5` | answers with megabytes of JSON |
| `/blackhole` | accepts the connection and holds it for 25 seconds |

## Layout

| Package | Contents |
| --- | --- |
| (root) | `Scenario`, `ScenarioCatalog`, `ScenarioService`, `ScenarioRunner`, `LoadDriver` |
| `db` | `DatabaseWorkload` — pool exhaustion, N+1, unbounded results, connection leak |
| `cache` | `CacheWorkload` — hit path, stampede, cold cache, unbounded local cache |
| `upstream` | `UpstreamServer`, `UpstreamWorkload` |
| `concurrency` | `ConcurrencyWorkload` — blocking calls, lock convoy |
