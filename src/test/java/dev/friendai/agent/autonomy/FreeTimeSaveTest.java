package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FreeTimeSaveTest {
    @Test
    void roundTripKeepsEverything() {
        var shift = new Shift(3, "get iron", List.of("has 3 iron_ingot"), "need a better pickaxe", 42, 1000, 2000,
                false, NeedKind.GEAR);
        var state = new FreeTime(true, FreeTime.Origin.COMMAND, "focus on food", 500, shift, false, 4,
                List.of(new ShiftRecord(2, "chop wood", NeedKind.STOCK, true, "16 logs", 400)),
                List.of("mine_blocks failed: no pickaxe"), Map.of(NeedKind.FARM, 9000L), List.of("smelted 8 iron"));
        assertEquals(state, FreeTimeSave.decode(FreeTimeSave.encode(state)).orElseThrow());
    }

    @Test
    void offRoundTrips() {
        assertEquals(FreeTime.OFF, FreeTimeSave.decode(FreeTimeSave.encode(FreeTime.OFF)).orElseThrow());
    }

    @Test
    void awaitingChoiceRoundTrips() {
        var state = new FreeTime(true, FreeTime.Origin.IDLE, "", 7, null, true, 1, List.of(), List.of(), Map.of(),
                List.of());
        assertEquals(state, FreeTimeSave.decode(FreeTimeSave.encode(state)).orElseThrow());
    }

    @Test
    void malformedIsEmpty() {
        assertTrue(FreeTimeSave.decode(null).isEmpty());
        assertTrue(FreeTimeSave.decode("goals:v1:{}").isEmpty());
        assertTrue(FreeTimeSave.decode("freetime:v1:{not json").isEmpty());
        assertTrue(FreeTimeSave.decode("freetime:v1:{\"on\":true,\"origin\":\"SOMETHING\"}").isEmpty());
        assertTrue(FreeTimeSave.decode("freetime:v1:" + "x".repeat(40_000)).isEmpty());
    }

    @Test
    void historyAndLessonsAreBounded() {
        var many = IntStream.range(0, 9)
                .mapToObj(i -> new ShiftRecord(i, "g", NeedKind.STOCK, true, "", i))
                .toList();
        var state = new FreeTime(true, FreeTime.Origin.IDLE, "w".repeat(300), 0, null, false, 1, many,
                List.of("oldest", "a", "b", "c", "d", "l".repeat(300)), Map.of(),
                IntStream.range(0, 12).mapToObj(i -> "line " + i).toList());
        assertEquals(FreeTime.MAX_HISTORY, state.history().size());
        assertEquals(0, state.history().get(0).id());
        assertEquals(FreeTime.MAX_LESSONS, state.lessons().size());
        assertEquals("a", state.lessons().get(0));
        assertEquals(FreeTime.MAX_LESSON, state.lessons().get(4).length());
        assertEquals(FreeTime.MAX_WISH, state.wish().length());
        assertEquals(FreeTime.MAX_AWAY, state.away().size());
        assertEquals("line 4", state.away().get(0));
    }

    @Test
    void defaultsMatchTheSpec() {
        AutonomySettings d = AutonomySettings.DEFAULTS;
        assertTrue(d.enabled() && d.idleStart() && d.whileOffline() && d.protectPlaces() && d.allowBuilding());
        assertEquals(5, d.idleMinutes());
        assertEquals(60, d.resumeAfterSec());
        assertEquals(20, d.shiftMinutes());
        assertEquals(6, d.maxShiftsPerHour());
        assertEquals(2, d.offlineShiftsPerHour());
        assertEquals(10, d.failCooldownMin());
        assertEquals(List.of("#minecraft:pickaxes", "#minecraft:axes", "#minecraft:swords", "minecraft:torch",
                "minecraft:shield"), d.keepItems());
    }
}
