package net.bitsar.sonify.core.json;

import net.bitsar.sonify.core.event.ContentionEvent;
import net.bitsar.sonify.core.event.GcEvent;
import java.util.List;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.event.ThreadSlice;
import net.bitsar.sonify.core.event.TickEvent;

/**
 * Serialises a {@link SonifyEvent} to the wire format the browser expects.
 *
 * <p>Written by hand rather than delegating to a JSON library so that core keeps zero
 * dependencies, the wire format is pinned by tests rather than by a mapper's defaults, and
 * numbers are formatted without consulting the default locale.
 */
public final class SonifyEventJson {

    private static final long[] POW10 = {1L, 10L, 100L, 1000L};
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SonifyEventJson() {
    }

    /** Returns the compact JSON object for one event. */
    public static String toJson(SonifyEvent event) {
        StringBuilder out = new StringBuilder(192);
        out.append("{\"type\":");
        appendString(out, event.type());
        out.append(",\"t\":").append(event.t());
        if (event instanceof TickEvent tick) {
            appendTick(out, tick);
        } else if (event instanceof GcEvent gc) {
            appendGc(out, gc);
        } else if (event instanceof ContentionEvent contention) {
            appendContention(out, contention);
        }
        return out.append('}').toString();
    }

    private static void appendTick(StringBuilder out, TickEvent tick) {
        out.append(",\"heapUsed\":").append(tick.heapUsed());
        out.append(",\"heapCommitted\":").append(tick.heapCommitted());
        out.append(",\"heapMax\":").append(tick.heapMax());
        out.append(",\"allocMBps\":");
        appendRounded(out, tick.allocMBps(), 1);
        out.append(",\"cpu\":");
        appendNullableRatio(out, tick.cpu());
        out.append(",\"machineCpu\":");
        appendNullableRatio(out, tick.machineCpu());
        out.append(",\"threads\":").append(tick.threads());
        out.append(",\"contended\":").append(tick.contended());
        out.append(",\"parked\":").append(tick.parked());
        out.append(",\"ioWait\":").append(tick.ioWait());
        out.append(",\"blocked\":").append(tick.blocked());
        out.append(",\"waiting\":").append(tick.waiting());
        appendThreadSlices(out, tick.threadSlices());
    }

    private static void appendThreadSlices(StringBuilder out, List<ThreadSlice> slices) {
        out.append(",\"threadDetail\":[");
        for (int index = 0; index < slices.size(); index++) {
            ThreadSlice slice = slices.get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"name\":");
            appendString(out, slice.name());
            out.append(",\"state\":");
            appendString(out, slice.state());
            out.append(",\"allocBytes\":").append(slice.allocBytes());
            out.append(",\"blocks\":").append(slice.blocks());
            out.append(",\"parks\":").append(slice.parks());
            out.append(",\"io\":").append(slice.ioWaits());
            out.append('}');
        }
        out.append(']');
    }

    private static void appendGc(StringBuilder out, GcEvent gc) {
        out.append(",\"name\":");
        appendString(out, gc.name());
        out.append(",\"cause\":");
        appendString(out, gc.cause());
        out.append(",\"pauseMs\":");
        appendRounded(out, gc.pauseMs(), 2);
        out.append(",\"durationMs\":");
        appendRounded(out, gc.durationMs(), 2);
        out.append(",\"heapUsedAfter\":").append(gc.heapUsedAfter());
        out.append(",\"heapCommittedAfter\":").append(gc.heapCommittedAfter());
        out.append(",\"reclaimedBytes\":").append(gc.reclaimedBytes());
    }

    private static void appendContention(StringBuilder out, ContentionEvent contention) {
        out.append(",\"thread\":");
        appendString(out, contention.thread());
        out.append(",\"monitor\":");
        appendString(out, contention.monitor());
        out.append(",\"durationMs\":");
        appendRounded(out, contention.durationMs(), 1);
    }

    private static void appendNullableRatio(StringBuilder out, Double value) {
        if (value == null || !Double.isFinite(value)) {
            out.append("null");
        } else {
            appendRounded(out, value, 3);
        }
    }

    private static void appendRounded(StringBuilder out, double value, int decimals) {
        if (!Double.isFinite(value)) {
            out.append('0');
            return;
        }
        long factor = POW10[decimals];
        long scaled = Math.round(Math.abs(value) * factor);
        if (value < 0 && scaled != 0) {
            out.append('-');
        }
        out.append(scaled / factor);
        long fraction = scaled % factor;
        if (fraction == 0) {
            return;
        }
        int start = out.length();
        out.append('.');
        for (long divisor = factor / 10; divisor > 0; divisor /= 10) {
            out.append((char) ('0' + (fraction / divisor) % 10));
        }
        while (out.charAt(out.length() - 1) == '0') {
            out.setLength(out.length() - 1);
        }
        if (out.length() - 1 == start) {
            out.setLength(start);
        }
    }

    private static StringBuilder appendString(StringBuilder out, String value) {
        if (value == null) {
            return out.append("null");
        }
        out.append('"');
        char[] characters = value.toCharArray();
        for (char character : characters) {
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) {
                        out.append("\\u00").append(HEX[(character >> 4) & 0xF]).append(HEX[character & 0xF]);
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"');
    }
}
