# sonify-scenarios

An ordinary Spring Boot application that degrades in eighteen different ways.

Full guide: **[docs/SCENARIOS.md](../docs/SCENARIOS.md)**.

## The point

None of this is "break the JVM" code. It is a controller, some services, JPA repositories, a
Redis-backed cache and two HTTP clients, written the way they are usually written. The clients
have timeouts, bounded retries with exponential backoff and jitter, and a circuit breaker. The
transactions are scoped, the queries are paginated, the caches have a TTL.

The pathologies emerge from that code anyway, which is the whole reason it is worth looking at.
A scenario called `nPlusOne()` teaches nothing; `listOrdersLazily()` iterating `order.getLines()`
teaches you to recognise it in your own work.

## Layout

```
domain/      Customer, CustomerOrder, OrderLine       JPA entities, lazy associations
repository/  OrderRepository, CustomerRepository      Spring Data JPA
service/     OrderService        three fetch strategies for one listing page
             CheckoutService     @Transactional spanning a gateway call, and the fix
             CustomerCacheService  Redis look-aside, and a guarded recompute
             InvoiceFormatter    a shared SimpleDateFormat behind a lock
             QuoteMemoizer       a ConcurrentHashMap keyed by something unique
client/      PaymentGatewayClient  retries, backoff, jitter, circuit breaker
             ShippingClient        a timeout fifty times the real latency
config/      HttpClientConfig, ScenariosJpaConfiguration
api/         OrderApiController    thin, the application's own endpoints
upstream/    UpstreamServer        the third party, in its own process
```

Plus the scenario harness itself: `ScenarioCatalog` (what each one is), `ScenarioService` (ids to
service calls), `ScenarioRunner` (in-JVM driver) and `LoadDriver` (external driver).

## Real dependencies

| | |
| --- | --- |
| PostgreSQL 16 | seeded: 20k customers, 120k orders, 600k order lines |
| Redis | a real server, used as a real cache |
| upstream | a JDK `HttpServer` in its own process on port 8090 |

```bash
java -cp ~/.sonify-build/sonify-scenarios/classes \
     net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090
```

| Path | Behaviour |
| --- | --- |
| `/fast` | answers immediately |
| `/slow?ms=2000` | answers after a delay |
| `/flaky?failPercent=70` | fails a configurable share of the time |
| `/huge?mb=5` | megabytes of JSON |
| `/blackhole` | accepts and holds for 25 seconds |

## Two drivers, and the difference matters

`ScenarioRunner` spawns threads **inside** the application. Convenient, enough for anything whose
signature is about the JVM itself, and **blind to the server's concurrency model**.

`LoadDriver` drives real connections **from a separate process**. Use it whenever the server is
the subject — it is the only way the Tomcat-versus-Netty comparison means anything.

```bash
java -cp ~/.sonify-build/sonify-scenarios/classes net.bitsar.sonify.scenarios.LoadDriver \
     http://localhost:8080/scenario/conc-thread-per-request/once 60 20
```

## Wiring note

This module configures its own `@EntityScan` and `@EnableJpaRepositories` in
`ScenariosJpaConfiguration`. A host application's `@ComponentScan` finds the services and finds
nothing at all in `domain` or `repository` — entity and repository discovery are separate
mechanisms, each defaulting to the main application's own package. A published library would do
this through auto-configuration instead; see [FINDINGS §18](../docs/FINDINGS.md).
