package dev.friendai.features.acquisition.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.crafting.CraftPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObtainBoundaryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void anAcquisitionBoundaryRoundTripsEverySavedField() {
        ObtainTask.Saved saved = new ObtainTask.Saved(
                1,
                ObtainTask.Phase.STATION,
                "minecraft:iron_pickaxe",
                3,
                false,
                true,
                12,
                900,
                48,
                64,
                300,
                "expedition",
                OWNER);

        assertEquals(
                saved, ObtainTask.Saved.decode(saved.encode()).orElseThrow(), "the saved boundary survives its codec");
    }

    @Test
    void anOmittedSettingIsSavedAsItsEffectiveValue() {
        ObtainTask.Saved saved = new ObtainTask.Saved(
                1, ObtainTask.Phase.LOCAL, "minecraft:oak_log", 4, true, true, 10, 600, 64, 128, 600, "local", OWNER);

        ObtainTask.Saved decoded = ObtainTask.Saved.decode(saved.encode()).orElseThrow();

        assertTrue(decoded.useContainers(), "an omitted use_containers reloads as the effective default");
        assertTrue(decoded.explore(), "an omitted explore reloads as the effective default");
    }

    @Test
    void aBoundaryFromAnotherVersionIsRefused() {
        ObtainTask.Saved saved = new ObtainTask.Saved(
                2, ObtainTask.Phase.LOCAL, "minecraft:oak_log", 4, true, true, 10, 600, 64, 128, 600, "local", OWNER);

        assertTrue(ObtainTask.Saved.decode(saved.encode()).isEmpty());
    }

    @Test
    void anOutOfRangeBoundaryIsRefused() {
        ObtainTask.Saved blankItem = new ObtainTask.Saved(
                1, ObtainTask.Phase.LOCAL, "  ", 4, true, true, 10, 600, 64, 128, 600, "local", OWNER);
        ObtainTask.Saved noTime = new ObtainTask.Saved(
                1, ObtainTask.Phase.LOCAL, "minecraft:oak_log", 4, true, true, 10, 0, 64, 128, 600, "local", OWNER);

        assertTrue(ObtainTask.Saved.decode(blankItem.encode()).isEmpty());
        assertTrue(ObtainTask.Saved.decode(noTime.encode()).isEmpty());
    }

    @Test
    void aBoundaryBiggerThanTheLimitIsNeitherWrittenNorRead() {
        ObtainTask.Saved oversized = new ObtainTask.Saved(
                1, ObtainTask.Phase.LOCAL, "x".repeat(3_000), 1, true, true, 10, 600, 64, 128, 600, "local", OWNER);

        assertThrows(IllegalStateException.class, oversized::encode, "an oversized boundary must not be written");
        assertTrue(ObtainTask.Saved.decode("{\"v\":1}").isEmpty(), "a malformed payload is refused, not guessed");
    }

    @Test
    void aBatchBoundaryRoundTripsItsTargetsAndPhase() {
        BatchObtainTask.Saved saved = new BatchObtainTask.Saved(
                1,
                BatchObtainTask.Phase.GATHER,
                List.of(
                        new CraftPlanner.Target("minecraft:stone_pickaxe", 2),
                        new CraftPlanner.Target("minecraft:stone_axe", 2)),
                true,
                false,
                32,
                12_000,
                32,
                96,
                400,
                "local",
                OWNER);

        assertEquals(saved, BatchObtainTask.Saved.decode(saved.encode()).orElseThrow());
    }

    @Test
    void aBatchBoundaryWithoutTargetsOrFromAnotherVersionIsRefused() {
        BatchObtainTask.Saved empty = new BatchObtainTask.Saved(
                1, BatchObtainTask.Phase.PLANNING, List.of(), true, true, 32, 12_000, 32, 96, 400, "local", OWNER);
        List<CraftPlanner.Target> tooMany = new ArrayList<>();
        for (int i = 0; i <= CraftPlanner.MAX_TARGETS; i++) {
            tooMany.add(new CraftPlanner.Target("minecraft:item_" + i, 1));
        }
        BatchObtainTask.Saved crowded = new BatchObtainTask.Saved(
                1, BatchObtainTask.Phase.PLANNING, tooMany, true, true, 32, 12_000, 32, 96, 400, "local", OWNER);
        BatchObtainTask.Saved future = new BatchObtainTask.Saved(
                3,
                BatchObtainTask.Phase.CRAFT,
                List.of(new CraftPlanner.Target("minecraft:torch", 8)),
                true,
                true,
                32,
                12_000,
                32,
                96,
                400,
                "local",
                OWNER);

        assertTrue(BatchObtainTask.Saved.decode(empty.encode()).isEmpty());
        assertTrue(BatchObtainTask.Saved.decode(crowded.encode()).isEmpty());
        assertTrue(BatchObtainTask.Saved.decode(future.encode()).isEmpty());
    }

    @Test
    void thePhaseFollowsTheStepThatWasRunning() {
        assertEquals(
                ObtainTask.Phase.NESTED_SEARCH,
                ObtainTask.phaseOf(new ObtainStep.Take("minecraft:oak_log", 4), SearchResourceTask.TYPE),
                "a search child always means a nested search, whatever the parent step was");
        assertEquals(
                ObtainTask.Phase.NESTED_SEARCH,
                ObtainTask.phaseOf(new ObtainStep.Explore("minecraft:stone", true, 0), null));
        assertEquals(ObtainTask.Phase.STATION, ObtainTask.phaseOf(new ObtainStep.Craft("minecraft:torch", 4), null));
        assertEquals(ObtainTask.Phase.LOCAL, ObtainTask.phaseOf(new ObtainStep.Take("minecraft:oak_log", 4), null));
        assertEquals(ObtainTask.Phase.PLANNING, ObtainTask.phaseOf(null, null));
        assertFalse(
                ObtainTask.phaseOf(null, null) == ObtainTask.Phase.NESTED_SEARCH,
                "an acquisition that has not started is not a search");
    }
}
