package net.bitsar.sonify.scenarios;

import java.time.Duration;

/**
 * How hard to drive a scenario.
 *
 * <p>Concurrency is the axis that matters: the same code path is healthy at five users and
 * pathological at two hundred, and seeing where it turns is the point of the exercise.
 */
public record ScenarioRequest(int users, Duration duration) {

    public static final int MAX_USERS = 500;

    public ScenarioRequest {
        users = Math.max(1, Math.min(MAX_USERS, users));
        if (duration == null || duration.isNegative() || duration.isZero()) {
            duration = Duration.ofSeconds(30);
        }
        if (duration.toSeconds() > 300) {
            duration = Duration.ofSeconds(300);
        }
    }

    public static ScenarioRequest of(int users, int seconds) {
        return new ScenarioRequest(users, Duration.ofSeconds(seconds));
    }
}
