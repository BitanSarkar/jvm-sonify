package net.bitsar.sonify.core.jfr;

import java.util.Map;

/**
 * Turns the collector names JFR reports into something readable in a UI.
 *
 * <p>JFR reports internal identifiers such as {@code G1New} and {@code ParallelScavenge}, not the
 * "G1 Young Generation" wording seen in management tooling.
 */
public final class GcNameFormatter {

    private static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("G1New", "G1 Young Generation"),
            Map.entry("G1Old", "G1 Old Generation"),
            Map.entry("G1Full", "G1 Full Collection"),
            Map.entry("ParallelScavenge", "Parallel Young Generation"),
            Map.entry("ParallelOld", "Parallel Old Generation"),
            Map.entry("SerialOld", "Serial Old Generation"),
            Map.entry("DefNew", "Serial Young Generation"),
            Map.entry("ParNew", "Parallel New Generation"),
            Map.entry("ConcurrentMarkSweep", "Concurrent Mark Sweep"),
            Map.entry("Z", "ZGC Cycle"),
            Map.entry("ZMinor", "ZGC Minor Cycle"),
            Map.entry("ZMajor", "ZGC Major Cycle"),
            Map.entry("Shenandoah", "Shenandoah Cycle"),
            Map.entry("ShenandoahFull", "Shenandoah Full Collection"),
            Map.entry("N/A", "Unknown Collector"));

    private GcNameFormatter() {
    }

    /** Returns a display name for a JFR collector name, splitting camel case when unrecognised. */
    public static String pretty(String jfrName) {
        if (jfrName == null || jfrName.isEmpty()) {
            return "Unknown Collector";
        }
        String known = KNOWN.get(jfrName);
        if (known != null) {
            return known;
        }
        return splitCamelCase(jfrName);
    }

    private static String splitCamelCase(String jfrName) {
        char[] characters = jfrName.toCharArray();
        StringBuilder out = new StringBuilder(characters.length + 4);
        for (int index = 0; index < characters.length; index++) {
            char character = characters[index];
            if (index > 0 && Character.isUpperCase(character) && startsWord(characters, index)) {
                out.append(' ');
            }
            out.append(character);
        }
        return out.toString();
    }

    private static boolean startsWord(char[] characters, int index) {
        if (!Character.isUpperCase(characters[index - 1])) {
            return true;
        }
        // The last capital of a run begins a new word: "GCSomething" is "GC Something".
        return index + 1 < characters.length && Character.isLowerCase(characters[index + 1]);
    }
}
