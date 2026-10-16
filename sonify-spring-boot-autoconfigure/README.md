# sonify-spring-boot-autoconfigure

Wiring, HTTP transports and the browser UI.

## How it decides what to register

| Condition | Effect |
| --- | --- |
| `sonify.enabled=true` | nothing at all is registered without it |
| `@ConditionalOnWebApplication(SERVLET)` | `SonifyMvcController`, returning an `SseEmitter` |
| `@ConditionalOnWebApplication(REACTIVE)` | `SonifyWebFluxController`, returning a `Flux` |
| `@ConditionalOnClass(Endpoint)` | read-only `/actuator/sonify` status endpoint |
| `@ConditionalOnClass(SecurityFilterChain)` | a startup log line, and **no security changes** |

Both transports are mapped at `${sonify.base-path}/stream` and send pre-serialised JSON as text,
so no message converter can re-quote it.

## The UI is served explicitly

`SonifyUiController` serves a fixed allowlist of five filenames from
`classpath:/META-INF/resources/sonify/`, rather than relying on Boot's default static locations.
That makes `sonify.base-path` genuinely move the UI, and means no other classpath resource can be
fetched through it.

## Security

**This module changes no security configuration, deliberately.** The stream exposes thread names,
monitor class names and heap figures. Whether that should be reachable is the application's
decision, so nothing is silently permitted. With Spring Security present, existing rules apply
unchanged:

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/sonify/**").permitAll()
        .anyRequest().authenticated());
```

## Properties

Everything is optional except `sonify.enabled`. See the table in the
[root README](../README.md#configuration).

## Tests

`SonifyAutoConfigurationTest` pins the conditions. `SonifyMvcStreamIT` and
`SonifyWebFluxStreamIT` run against a real random-port server, provoke allocation and contention,
and assert the same shape through one shared helper so the transports cannot drift.

```bash
mvn -pl sonify-spring-boot-autoconfigure -am test
mvn -Pboot4 -pl sonify-spring-boot-autoconfigure -am test   # against Spring Boot 4.1.1
```
