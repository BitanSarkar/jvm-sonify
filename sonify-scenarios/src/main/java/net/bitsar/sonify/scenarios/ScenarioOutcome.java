package net.bitsar.sonify.scenarios;

/**
 * What actually happened, so a scenario can be judged on numbers and not only on how it looked.
 *
 * <p>Latency percentiles matter as much as the JVM signature: a scenario where throughput halves
 * and p99 goes through the roof is the interesting one, even when heap and CPU look unremarkable.
 *
 * <p>Recorded in microseconds. A lock convoy whose individual acquisitions take sixty
 * microseconds reported every percentile as zero in milliseconds, which said nothing at all
 * about the queue that was plainly forming behind it.
 */
public record ScenarioOutcome(String scenarioId,
                              int users,
                              long elapsedMs,
                              long completed,
                              long failed,
                              double throughputPerSecond,
                              long p50Micros,
                              long p95Micros,
                              long p99Micros,
                              long maxMicros,
                              String note) {
}
