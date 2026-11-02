package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.text.UserMessage;
import org.junit.jupiter.api.Test;

class AwaySummaryTest {
    @Test
    void keepsNewestEight() {
        FreeTime state = FreeTime.OFF;
        for (int i = 0; i < 10; i++) {
            state = AwaySummary.add(state, "line " + i);
        }
        assertEquals(FreeTime.MAX_AWAY, state.away().size());
        assertEquals(
                "friendai.freetime.away_older_omitted",
                AwaySummary.translationKey(state.away().getFirst()).orElseThrow());
        assertEquals("line 3", state.away().get(1));
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

    @Test
    void translatedAwayLinesKeepTheirKeyUntilTheOwnerReturns() {
        FreeTime state = AwaySummary.addMessage(FreeTime.OFF, UserMessage.of("friendai.freetime.shift_report.failed"));

        AwaySummary.Taken taken = AwaySummary.take(state);

        assertEquals(1, taken.lines().size());
        assertEquals(
                "friendai.freetime.shift_report.failed",
                AwaySummary.translationKey(taken.lines().getFirst()).orElseThrow());
        assertTrue(taken.state().away().isEmpty());

        FreeTime restored = FreeTimeSave.decode(FreeTimeSave.encode(state)).orElseThrow();
        assertEquals(
                "friendai.freetime.shift_report.failed",
                AwaySummary.translationKey(restored.away().getFirst()).orElseThrow());
    }

    @Test
    void translatedAwayLinesKeepTheirArgumentsUntilTheOwnerReturns() {
        FreeTime state =
                AwaySummary.addMessage(FreeTime.OFF, UserMessage.of("friendai.freetime.stage_reached", "diamond"));

        AwaySummary.Taken taken = AwaySummary.take(state);

        UserMessage message =
                AwaySummary.translationMessage(taken.lines().getFirst()).orElseThrow();
        assertEquals("friendai.freetime.stage_reached", message.key());
        assertEquals(java.util.List.of("diamond"), message.args());
        FreeTime restored = FreeTimeSave.decode(FreeTimeSave.encode(state)).orElseThrow();
        assertEquals(
                message,
                AwaySummary.translationMessage(restored.away().getFirst()).orElseThrow());
    }
}
