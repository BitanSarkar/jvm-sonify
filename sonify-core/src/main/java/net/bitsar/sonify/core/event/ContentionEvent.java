package net.bitsar.sonify.core.event;

/** A thread blocked longer than the configured threshold trying to enter a monitor. */
public record ContentionEvent(long t,
                              String thread,
                              String monitor,
                              double durationMs) implements SonifyEvent {

    @Override
    public String type() {
        return "contention";
    }
}
