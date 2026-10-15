package net.bitsar.sonify.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import net.bitsar.sonify.core.event.ContentionEvent;
import net.bitsar.sonify.core.event.SonifyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventBufferTest {

    private static ContentionEvent event(int index) {
        return new ContentionEvent(index, "t" + index, "Monitor", 1d);
    }

    @Test
    @DisplayName("keeps the newest events and reports how many were dropped")
    void dropsOldestWhenFull() throws InterruptedException {
        EventBuffer buffer = new EventBuffer(3);
        for (int index = 0; index < 5; index++) {
            buffer.offer(event(index));
        }

        assertEquals(3, buffer.size());
        assertEquals(2, buffer.dropped());
        assertEquals(List.of(2L, 3L, 4L), drain(buffer));
    }

    @Test
    @DisplayName("a buffer that is never full drops nothing")
    void keepsEverythingWithinCapacity() throws InterruptedException {
        EventBuffer buffer = new EventBuffer(8);
        for (int index = 0; index < 8; index++) {
            buffer.offer(event(index));
        }

        assertEquals(0, buffer.dropped());
        assertEquals(8, buffer.capacity());
        assertEquals(8, drain(buffer).size());
    }

    @Test
    @DisplayName("polling an empty buffer times out instead of blocking forever")
    void pollReturnsNullWhenEmpty() throws InterruptedException {
        assertNull(new EventBuffer(2).poll(Duration.ofMillis(5)));
    }

    @Test
    @DisplayName("a capacity of one always holds only the latest event")
    void capacityOfOneHoldsLatest() throws InterruptedException {
        EventBuffer buffer = new EventBuffer(1);
        buffer.offer(event(1));
        buffer.offer(event(2));

        assertEquals(List.of(2L), drain(buffer));
        assertEquals(1, buffer.dropped());
    }

    private static List<Long> drain(EventBuffer buffer) throws InterruptedException {
        List<Long> seen = new java.util.ArrayList<>();
        SonifyEvent next;
        while ((next = buffer.poll(Duration.ofMillis(5))) != null) {
            seen.add(next.t());
        }
        return seen;
    }
}
