package net.bitsar.sonify.scenarios;

/**
 * What actually happened, so a scenario can be judged on numbers and not only on how it looked.
 *
 * <p>Latency percentiles matter as much as the JVM signature: a scenario where throughput halves
 * and p99 goes through the roof is the interesting one, even when heap and CPU look unremarkable.
 */
public record ScenarioOutcome(String scenarioId,
                              int users,
                              long elapsedMs,
                              long completed,
                              long failed,
                              double throughputPerSecond,
                              long p50Ms,
                              long p95Ms,
                              long p99Ms,
                              long maxMs,
                              String note) {
}
