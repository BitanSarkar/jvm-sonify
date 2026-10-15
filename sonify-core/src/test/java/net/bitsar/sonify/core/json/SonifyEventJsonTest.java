package net.bitsar.sonify.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.bitsar.sonify.core.event.ContentionEvent;
import net.bitsar.sonify.core.event.GcEvent;
import net.bitsar.sonify.core.event.ThreadSlice;
import net.bitsar.sonify.core.event.TickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SonifyEventJsonTest {

    @Test
    @DisplayName("a tick serialises to the documented wire format")
    void tick() {
        TickEvent tick = new TickEvent(1734567890123L, 512000000L, 1073741824L, 2147483648L,
                220.4d, 0.37d, 0.5d, 42, 3, 7, List.of());

        assertEquals("{\"type\":\"tick\",\"t\":1734567890123,\"heapUsed\":512000000,"
                + "\"heapCommitted\":1073741824,\"heapMax\":2147483648,\"allocMBps\":220.4,"
                + "\"cpu\":0.37,\"machineCpu\":0.5,\"threads\":42,\"contended\":3,\"parked\":7,"
                + "\"threadDetail\":[]}", SonifyEventJson.toJson(tick));
    }

    @Test
    @DisplayName("an unavailable CPU reading is null, never a misleading zero")
    void unavailableCpuIsNull() {
        TickEvent tick = new TickEvent(1L, 0L, 0L, 0L, 0d, null, null, 1, 0, 0, List.of());

        assertEquals("{\"type\":\"tick\",\"t\":1,\"heapUsed\":0,\"heapCommitted\":0,\"heapMax\":0,"
                + "\"allocMBps\":0,\"cpu\":null,\"machineCpu\":null,\"threads\":1,\"contended\":0,"
                + "\"parked\":0,\"threadDetail\":[]}", SonifyEventJson.toJson(tick));
    }

    @Test
    @DisplayName("thread detail carries each thread's state and activity")
    void threadDetail() {
        TickEvent tick = new TickEvent(1L, 0L, 0L, 0L, 0d, null, null, 2, 1, 0,
                List.of(new ThreadSlice("worker-1", ThreadSlice.CONTENDED, 4194304L, 2, 0)));

        assertEquals("{\"type\":\"tick\",\"t\":1,\"heapUsed\":0,\"heapCommitted\":0,\"heapMax\":0,"
                + "\"allocMBps\":0,\"cpu\":null,\"machineCpu\":null,\"threads\":2,\"contended\":1,"
                + "\"parked\":0,\"threadDetail\":[{\"name\":\"worker-1\",\"state\":\"contended\","
                + "\"allocBytes\":4194304,\"blocks\":2,\"parks\":0}]}", SonifyEventJson.toJson(tick));
    }

    @Test
    @DisplayName("a collection reports pause, duration and what it reclaimed")
    void gc() {
        GcEvent gc = new GcEvent(1734567890456L, "G1 Young Generation", "G1 Evacuation Pause",
                12.7d, 12.7d, 25000000L, 110000000L, 62000000L);

        assertEquals("{\"type\":\"gc\",\"t\":1734567890456,\"name\":\"G1 Young Generation\","
                + "\"cause\":\"G1 Evacuation Pause\",\"pauseMs\":12.7,\"durationMs\":12.7,"
                + "\"heapUsedAfter\":25000000,\"heapCommittedAfter\":110000000,"
                + "\"reclaimedBytes\":62000000}", SonifyEventJson.toJson(gc));
    }

    @Test
    @DisplayName("a collection with no heap summary sends nulls rather than zeros")
    void gcWithoutHeapSummary() {
        GcEvent gc = new GcEvent(2L, "ZGC Cycle", "Warmup", 0.5d, 41d, null, null, null);

        assertEquals("{\"type\":\"gc\",\"t\":2,\"name\":\"ZGC Cycle\",\"cause\":\"Warmup\","
                + "\"pauseMs\":0.5,\"durationMs\":41,\"heapUsedAfter\":null,"
                + "\"heapCommittedAfter\":null,\"reclaimedBytes\":null}", SonifyEventJson.toJson(gc));
    }

    @Test
    @DisplayName("a contention event names the thread and the monitor")
    void contention() {
        ContentionEvent contention = new ContentionEvent(1734567890789L,
                "http-nio-8080-exec-4", "example.Cache", 48.2d);

        assertEquals("{\"type\":\"contention\",\"t\":1734567890789,"
                + "\"thread\":\"http-nio-8080-exec-4\",\"monitor\":\"example.Cache\","
                + "\"durationMs\":48.2}", SonifyEventJson.toJson(contention));
    }

    @Test
    @DisplayName("thread names that contain JSON syntax cannot break the stream")
    void escapesStrings() {
        ContentionEvent contention = new ContentionEvent(1L, "say \"hi\"\n\tnow", "a\\b", 1d);

        assertEquals("{\"type\":\"contention\",\"t\":1,\"thread\":\"say \\\"hi\\\"\\n\\tnow\","
                + "\"monitor\":\"a\\\\b\",\"durationMs\":1}", SonifyEventJson.toJson(contention));
    }

    @Test
    @DisplayName("control characters are escaped as unicode")
    void escapesControlCharacters() {
        ContentionEvent contention = new ContentionEvent(1L, "a\u0001b", "M", 1d);

        assertEquals("{\"type\":\"contention\",\"t\":1,\"thread\":\"a\\u0001b\","
                + "\"monitor\":\"M\",\"durationMs\":1}", SonifyEventJson.toJson(contention));
    }

    @Test
    @DisplayName("rounding never emits a trailing zero or a locale decimal comma")
    void rounding() {
        assertEquals("\"durationMs\":1", field(1.0d));
        assertEquals("\"durationMs\":1.5", field(1.5d));
        assertEquals("\"durationMs\":1.5", field(1.46d));
        assertEquals("\"durationMs\":0.1", field(0.14d));
        assertEquals("\"durationMs\":0", field(0.04d));
        assertEquals("\"durationMs\":0", field(Double.NaN));
    }

    private static String field(double durationMs) {
        String json = SonifyEventJson.toJson(new ContentionEvent(1L, "t", "M", durationMs));
        return json.substring(json.lastIndexOf("\"durationMs\""), json.length() - 1);
    }
}
