package net.bitsar.sonify.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GcNameFormatterTest {

    @Test
    @DisplayName("the names JFR actually emits become the names people recognise")
    void knownCollectors() {
        assertEquals("G1 Young Generation", GcNameFormatter.pretty("G1New"));
        assertEquals("G1 Old Generation", GcNameFormatter.pretty("G1Old"));
        assertEquals("G1 Full Collection", GcNameFormatter.pretty("G1Full"));
        assertEquals("Parallel Young Generation", GcNameFormatter.pretty("ParallelScavenge"));
        assertEquals("Serial Old Generation", GcNameFormatter.pretty("SerialOld"));
        assertEquals("ZGC Cycle", GcNameFormatter.pretty("Z"));
        assertEquals("Shenandoah Cycle", GcNameFormatter.pretty("Shenandoah"));
    }

    @Test
    @DisplayName("an unrecognised collector is split on camel case rather than shown raw")
    void unknownCollectorsAreSplit() {
        assertEquals("Some Future Collector", GcNameFormatter.pretty("SomeFutureCollector"));
        assertEquals("GC Something", GcNameFormatter.pretty("GCSomething"));
        assertEquals("lowercase", GcNameFormatter.pretty("lowercase"));
    }

    @Test
    @DisplayName("a missing name never produces null or an empty label")
    void missingNames() {
        assertEquals("Unknown Collector", GcNameFormatter.pretty(null));
        assertEquals("Unknown Collector", GcNameFormatter.pretty(""));
        assertEquals("Unknown Collector", GcNameFormatter.pretty("N/A"));
    }
}
