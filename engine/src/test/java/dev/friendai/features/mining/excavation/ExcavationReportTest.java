package dev.friendai.features.mining.excavation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExcavationReportTest {
    @Test
    void successNamesOnlyMeasuredCounters() {
        ExcavationProgress progress = new ExcavationProgress();
        progress.markBroken();
        progress.addTaken("minecraft:cobblestone", 2);
        progress.markSkipped("protected");

        assertEquals(
                "mined 1 stone and took 2 minecraft:cobblestone; skipped 1 that could not be taken",
                ExcavationReport.success("stone", progress));
    }

    @Test
    void partialNamesTheStopReason() {
        ExcavationProgress progress = new ExcavationProgress();
        progress.markBroken();
        progress.markBroken();

        assertEquals(
                "mined 2 stone; stopped: the inventory is full",
                ExcavationReport.partial("stone", progress, "the inventory is full"));
    }

    @Test
    void alreadyEmptyCellsAreReportedAsCompletedSpace() {
        ExcavationProgress progress = new ExcavationProgress();
        progress.markSkipped("already empty");
        progress.markSkipped("already empty");

        assertEquals("mined 0 shaft; 2 already empty", ExcavationReport.success("shaft", progress));
    }
}
