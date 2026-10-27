package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AwaySummaryTest {
    @Test
    void keepsNewestEight() {
        FreeTime state = FreeTime.OFF;
        for (int i = 0; i < 10; i++) {
            state = AwaySummary.add(state, "line " + i);
        }
        assertEquals(FreeTime.MAX_AWAY, state.away().size());
        assertEquals("line 2", state.away().get(0));
        assertEquals("line 9", state.away().getLast());
    }

    @Test
    void takeJoinsAndClears() {
        FreeTime state = AwaySummary.add(AwaySummary.add(FreeTime.OFF, "chopped 16 logs"), "smelted 8 iron");
        AwaySummary.Taken taken = AwaySummary.take(state);
        assertEquals("chopped 16 logs; smelted 8 iron", taken.text());
        assertTrue(taken.state().away().isEmpty());
    }

    @Test
    void blankLinesAreIgnored() {
        assertTrue(AwaySummary.add(FreeTime.OFF, "  ").away().isEmpty());
    }

    @Test
    void takeEmptyIsEmpty() {
        assertEquals("", AwaySummary.take(FreeTime.OFF).text());
    }
}
