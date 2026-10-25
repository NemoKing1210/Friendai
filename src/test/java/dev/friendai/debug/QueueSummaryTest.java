package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueueSummaryTest {
    @Test
    void parsesCountersAndLines() {
        var s = QueueSummary.parse("Task queue: 1 running, 2 pending, 3 blocked; plans: 4\n"
                        + "Running: #25 direct: mining 32 minecraft:tall_grass\n"
                        + "Next: #26 (direct) go_to; #27 (house) build\n"
                        + "Blocked: #30 plan \"house\" craft_item: no planks")
                .orElseThrow();
        assertEquals(1, s.running());
        assertEquals(2, s.pending());
        assertEquals(3, s.blocked());
        assertEquals(4, s.plans());
        assertEquals("#25 direct: mining 32 minecraft:tall_grass", s.runningLine());
        assertEquals("#26 (direct) go_to; #27 (house) build", s.nextLine());
        assertEquals("#30 plan \"house\" craft_item: no planks", s.blockedLine());
    }

    @Test
    void blockedCounterIsOptional() {
        var s = QueueSummary.parse("Task queue: 1 running, 0 pending; plans: 0\nRunning: #1 direct: follow").orElseThrow();
        assertEquals(0, s.blocked());
        assertEquals("", s.nextLine());
        assertEquals("", s.blockedLine());
    }

    @Test
    void plainTextIsNotAQueue() {
        assertTrue(QueueSummary.parse("Task queue: empty").isEmpty());
        assertTrue(QueueSummary.parse("follow").isEmpty());
        assertTrue(QueueSummary.parse("").isEmpty());
        assertTrue(QueueSummary.parse(null).isEmpty());
    }
}
