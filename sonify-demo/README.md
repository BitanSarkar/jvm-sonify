# sonify-demo

The Tomcat application: thread per request, port 8080.

```bash
java -Xmx512m -jar sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar
```

Open <http://localhost:8080/sonify/> and press **Play sound**.

## Synthetic load

Direct and crude, for seeing the monitor work without any infrastructure:

| Endpoint | Effect |
| --- | --- |
| `GET /load/alloc?mb=400` | allocates garbage fast |
| `GET /load/lock?threads=16&ms=50` | threads queue for one monitor, for 15s |
| `GET /load/leak?mb=150` | retains memory; capped at a third of the heap |
| `GET /load/calm` | stops everything and releases it |
| `GET /benchmark?mb=1500` | fixed deterministic workload, reports its own CPU cost |

## Real scenarios

Need PostgreSQL, Redis and the upstream process running. See
[docs/SCENARIOS.md](../docs/SCENARIOS.md).

```bash
./sonify-demo/scripts/scenarios.sh list
./sonify-demo/scripts/scenarios.sh run db-n-plus-one 50 30
./sonify-demo/scripts/scenarios.sh all
```

## Scripts

| Script | Purpose |
| --- | --- |
| `scenarios.sh` | drive the sixteen scenarios, in-process or over HTTP |
| `story.sh` | calm → spike → contention → leak → recovery, narrated. The reel. |
| `overhead.sh` | paired ABBA experiment measuring what Sonify costs |
| `_format.py` | output formatting for `scenarios.sh` |

## Configuration

`application.properties` sets a deliberately small Hikari pool (10 connections), because most of
the database scenarios are about what happens when demand exceeds it.
