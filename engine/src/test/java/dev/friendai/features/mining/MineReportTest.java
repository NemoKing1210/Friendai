package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MineReportTest {

    @Test
    void takenDropsAreCounted() {
        String text = MineReport.success(
                "minecraft:coal_ore",
                1,
                4,
                5,
                6,
                List.of(new MineReport.Taken("minecraft:coal", 2)),
                List.of(),
                true,
                "");
        assertEquals("mined minecraft:coal_ore at 4 5 6 and took 2 minecraft:coal", text);
    }

    @Test
    void aVeinNamesHowManyBlocksAndWhatWasTaken() {
        String text = MineReport.success(
                "minecraft:coal_ore",
                3,
                4,
                5,
                6,
                List.of(new MineReport.Taken("minecraft:coal", 3)),
                List.of(),
                true,
                "");
        assertEquals("mined 3 minecraft:coal_ore at 4 5 6 and took 3 minecraft:coal", text);
    }

    @Test
    void anUntakenDropCarriesItsCellAndTheNextTool() {
        String text = MineReport.success(
                "minecraft:coal_ore",
                1,
                4,
                5,
                6,
                List.of(),
                List.of(new MineReport.Untaken("minecraft:coal", 1, 7, 8, 9)),
                true,
                "");
        assertTrue(text.startsWith("mined minecraft:coal_ore at 4 5 6 but took nothing"), text);
        assertTrue(text.contains("1 minecraft:coal is still at 7 8 9"), text);
        assertTrue(text.contains("pickup_items"), text);
    }

    @Test
    void severalUntakenDropsNameTheirCellsAndTheRest() {
        String text = MineReport.success(
                "minecraft:coal_ore",
                2,
                4,
                5,
                6,
                List.of(),
                List.of(
                        new MineReport.Untaken("minecraft:coal", 1, 7, 8, 9),
                        new MineReport.Untaken("minecraft:coal", 1, 10, 8, 9),
                        new MineReport.Untaken("minecraft:coal", 1, 11, 8, 9)),
                true,
                "");
        assertTrue(text.contains("3 drops are still at 7 8 9 and 10 8 9 and 1 more"), text);
    }

    @Test
    void aBlockThatDroppedNothingSaysSoInsteadOfClaimingSuccess() {
        String text = MineReport.success("minecraft:glass", 1, 4, 5, 6, List.of(), List.of(), false, "");
        assertEquals("mined minecraft:glass at 4 5 6; it dropped nothing I could see", text);
    }

    @Test
    void aVanishedDropIsNotReportedAsTaken() {
        String text = MineReport.success("minecraft:coal_ore", 1, 4, 5, 6, List.of(), List.of(), true, "");
        assertTrue(text.contains("its drop is gone"), text);
    }

    @Test
    void aStoppedVeinNamesItsCause() {
        String text = MineReport.success(
                "minecraft:coal_ore",
                2,
                4,
                5,
                6,
                List.of(new MineReport.Taken("minecraft:coal", 2)),
                List.of(),
                true,
                "stopped at a protected neighbour at 7 5 6");
        assertTrue(text.endsWith("; stopped at a protected neighbour at 7 5 6"), text);
    }

    @Test
    void aRefusalSaysWhatWasNotMined() {
        assertEquals(
                "I did not mine minecraft:coal_ore at 4 5 6: the inventory is full",
                MineReport.notMined("minecraft:coal_ore", 4, 5, 6, "the inventory is full"));
    }
}
