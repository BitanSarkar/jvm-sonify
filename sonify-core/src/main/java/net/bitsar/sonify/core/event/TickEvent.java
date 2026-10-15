package net.bitsar.sonify.core.event;

import java.util.List;

/**
 * A periodic snapshot of every vital sign.
 *
 * <p>{@code cpu} and {@code machineCpu} are nullable on purpose: a JVM that cannot report CPU
 * load sends {@code null} so the UI can show "no reading" instead of a convincing zero.
 * {@code threadSlices} describes the most active threads in this window, most active first.
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
                        List<ThreadSlice> threadSlices) implements SonifyEvent {

    public TickEvent {
        threadSlices = threadSlices == null ? List.of() : List.copyOf(threadSlices);
    }

    @Override
    public String type() {
        return "tick";
    }
}
