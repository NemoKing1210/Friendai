package dev.friendai.features.acquisition.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.crafting.Station;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BatchObtainTaskTest {
    private static final CraftPlanner.RawNeed ANDESITE =
            new CraftPlanner.RawNeed("minecraft:andesite", Set.of("minecraft:andesite"), 4);

    private static final CraftPlanner.RawNeed STONECUTTER =
            new CraftPlanner.RawNeed("minecraft:stonecutter", Set.of("minecraft:stonecutter"), 1, Station.STONECUTTER);

    @Test
    void anObtainableNeedPrecedesAStationNeed() {
        // Gathering material is always progress; making a station may be impossible where the companion stands.
        assertEquals(ANDESITE, BatchObtainTask.nextNeed(List.of(STONECUTTER, ANDESITE)));
        assertEquals(STONECUTTER, BatchObtainTask.nextNeed(List.of(STONECUTTER)));
    }
}
