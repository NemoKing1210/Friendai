package dev.friendai.task.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.friendai.agent.autonomy.AutonomySettings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeepPolicyTest {
    private static final List<String> KEEP = AutonomySettings.DEFAULT_KEEP;

    private static KeepPolicy.Slot tool(int index, String id, String tag, String kind, int tier) {
        return new KeepPolicy.Slot(index, id, Set.of(tag), 1, kind, tier, 0, -1);
    }

    private static KeepPolicy.Slot item(int index, String id, int count) {
        return new KeepPolicy.Slot(index, id, Set.of(), count, "", 0, 0, -1);
    }

    private static KeepPolicy.Slot food(int index, String id, int count, int points) {
        return new KeepPolicy.Slot(index, id, Set.of(), count, "", 0, points, -1);
    }

    private static KeepPolicy.Slot block(int index, String id, int count, double price) {
        return new KeepPolicy.Slot(index, id, Set.of(), count, "", 0, 0, price);
    }

    @Test
    void keepsBestPickaxeOnly() {
        Map<Integer, Integer> keep = KeepPolicy.keep(
                List.of(
                        tool(0, "minecraft:wooden_pickaxe", "minecraft:pickaxes", "PICKAXE", 1),
                        tool(1, "minecraft:iron_pickaxe", "minecraft:pickaxes", "PICKAXE", 3),
                        tool(2, "minecraft:stone_axe", "minecraft:axes", "AXE", 2)),
                KEEP);
        assertNull(keep.get(0));
        assertEquals(1, keep.get(1));
        assertEquals(1, keep.get(2));
    }

    @Test
    void keepsShieldAndTorchesFromKeepItems() {
        Map<Integer, Integer> keep =
                KeepPolicy.keep(List.of(item(0, "minecraft:shield", 1), item(1, "minecraft:torch", 40)), KEEP);
        assertEquals(1, keep.get(0));
        assertEquals(40, keep.get(1));
    }

    @Test
    void keepsFoodUpToTwentyPoints() {
        Map<Integer, Integer> keep = KeepPolicy.keep(
                List.of(
                        food(0, "minecraft:bread", 10, 5),
                        food(1, "minecraft:bread", 10, 5),
                        food(2, "minecraft:cooked_beef", 2, 8)),
                KEEP);
        assertEquals(2, keep.get(2), "the best food first: two steaks are 16 points");
        assertEquals(1, keep.get(0), "one bread tops it up to 20");
        assertNull(keep.get(1));
    }

    @Test
    void keepsCheapestBlocksUpToThirtyTwo() {
        Map<Integer, Integer> keep = KeepPolicy.keep(
                List.of(block(0, "minecraft:oak_log", 10, 5.0), block(1, "minecraft:cobblestone", 52, 1.0)), KEEP);
        assertEquals(32, keep.get(1));
        assertNull(keep.get(0));
    }

    @Test
    void keepItemsTagsMatch() {
        Map<Integer, Integer> keep = KeepPolicy.keep(
                List.of(new KeepPolicy.Slot(0, "minecraft:emerald", Set.of("c:gems"), 3, "", 0, 0, -1)),
                List.of("#c:gems"));
        assertEquals(3, keep.get(0));
    }

    @Test
    void depositsOres() {
        Map<Integer, Integer> keep = KeepPolicy.keep(List.of(item(0, "minecraft:raw_iron", 12)), KEEP);
        assertFalse(keep.containsKey(0));
    }
}
