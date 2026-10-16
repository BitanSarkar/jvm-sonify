# sonify-spring-boot-starter

The single dependency you add. A pom with no code, pulling in `sonify-core` and
`sonify-spring-boot-autoconfigure`.

```xml
<dependency>
  <groupId>net.bitsar</groupId>
  <artifactId>sonify-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```properties
sonify.enabled=true
```

Then open `/sonify/`. No code changes, no annotations, no wiring.

Without `sonify.enabled=true` the starter is **inert**: no beans, no endpoints, no threads,
nothing measured.
