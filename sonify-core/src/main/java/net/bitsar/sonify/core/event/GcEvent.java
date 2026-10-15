package net.bitsar.sonify.core.event;

/**
 * A single garbage collection.
 *
 * <p>{@code pauseMs} is the summed stop-the-world time, which is what the application actually
 * felt; {@code durationMs} is the collection's wall-clock span and may be much larger for a
 * concurrent collector. The heap figures come from the collection's own heap summary and are
 * null if the JVM did not supply one. {@code reclaimedBytes} is the drop across the collection:
 * a large heap reclaiming almost nothing is the signature of a leak.
 */
public record GcEvent(long t,
                      String name,
                      String cause,
                      double pauseMs,
                      double durationMs,
                      Long heapUsedAfter,
                      Long heapCommittedAfter,
                      Long reclaimedBytes) implements SonifyEvent {

    @Override
    public String type() {
        return "gc";
    }
}
