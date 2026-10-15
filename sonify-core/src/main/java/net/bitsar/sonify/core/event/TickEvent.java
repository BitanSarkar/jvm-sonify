package net.bitsar.sonify.core.event;

import java.util.List;

/**
 * A periodic snapshot of every vital sign.
 *
 * <p>{@code cpu} and {@code machineCpu} are nullable on purpose: a JVM that cannot report CPU
 * load sends {@code null} so the UI can show "no reading" instead of a convincing zero.
 *
 * <p>{@code blocked} and {@code waiting} are instantaneous thread-state counts from the platform
 * thread bean, and they are the only readings that stay truthful when nothing ever recovers:
 * Flight Recorder reports a wait only once it ends, so a drained pool or a client with no timeout
 * emits nothing at all while every thread sits stuck.
 *
 * <p>{@code ioWait} counts threads blocked in a socket read, which is how waiting on a database,
 * a cache or a third-party API actually appears. It is kept separate from {@code parked} because
 * the two mean very different things: a parked thread is usually an idle pool thread with nothing
 * to do, while a thread in a socket read is a request that cannot finish.
 */
public record TickEvent(long t,
                        long heapUsed,
                        long heapCommitted,
                        long heapMax,
                        double allocMBps,
                        Double cpu,
                        Double machineCpu,
                        int threads,
                        int contended,
                        int parked,
                        int ioWait,
                        int blocked,
                        int waiting,
                        List<ThreadSlice> threadSlices) implements SonifyEvent {

    public TickEvent {
        threadSlices = threadSlices == null ? List.of() : List.copyOf(threadSlices);
    }

    @Override
    public String type() {
        return "tick";
    }
}
