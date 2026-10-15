package net.bitsar.sonify.core.event;

/**
 * One observation about the running JVM, as delivered to a browser.
 *
 * <p>A {@link TickEvent} is a periodic snapshot of every vital sign. {@link GcEvent} and
 * {@link ContentionEvent} are discrete occurrences, published the moment they are observed.
 */
public sealed interface SonifyEvent permits TickEvent, GcEvent, ContentionEvent {

    /** Wire discriminator, also used as the SSE {@code event:} name. */
    String type();

    /** Wall-clock milliseconds since the epoch at which this observation was made. */
    long t();
}
