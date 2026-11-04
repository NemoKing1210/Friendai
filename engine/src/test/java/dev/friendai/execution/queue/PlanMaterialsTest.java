package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanMaterialsTest {
    /** stone drops cobblestone, as every other mined block does through its loot table. */
    private static final PlanMaterials.ToItemIds STONE_TO_COBBLE = blockId ->
            "minecraft:stone".equals(blockId) ? List.of("minecraft:stone", "minecraft:cobblestone") : List.of(blockId);

    private static QueuedStep step(String tool, String argsJson) {
        JsonObject args = JsonParser.parseString(argsJson).getAsJsonObject();
        return new QueuedStep(tool, args, null, null, null, null, 1, null);
    }

    @Test
    void obtainingCraftingAndSmeltingReserveTheItemAndItsCount() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(
                        step("obtain_item", "{\"item\":\"minecraft:stone_pickaxe\",\"count\":2}"),
                        step("craft_item", "{\"item\":\"minecraft:stone_axe\",\"count\":1}"),
                        step("smelt_items", "{\"item\":\"minecraft:oak_log\",\"count\":3}")),
                STONE_TO_COBBLE);

        assertEquals(2, needs.get("minecraft:stone_pickaxe"));
        assertEquals(1, needs.get("minecraft:stone_axe"));
        assertEquals(3, needs.get("minecraft:oak_log"));
    }

    @Test
    void miningABlockReservesEveryItemItYields() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(step("mine_blocks", "{\"block\":\"minecraft:stone\",\"count\":24}")), STONE_TO_COBBLE);

        assertEquals(24, needs.get("minecraft:cobblestone"), "the cobblestone the tools are made of");
        assertEquals(24, needs.get("minecraft:stone"));
    }

    @Test
    void theSameItemInTwoStepsSums() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(
                        step("obtain_item", "{\"item\":\"minecraft:cobblestone\",\"count\":4}"),
                        step("mine_blocks", "{\"block\":\"minecraft:stone\",\"count\":6}")),
                STONE_TO_COBBLE);

        assertEquals(10, needs.get("minecraft:cobblestone"));
    }

    @Test
    void aMissingCountMeansOne() {
        assertEquals(
                1,
                PlanMaterials.needs(List.of(step("craft_item", "{\"item\":\"minecraft:stick\"}")), STONE_TO_COBBLE)
                        .get("minecraft:stick"));
    }

    @Test
    void stepsThatNeedNoMaterialContributeNothing() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(
                        step("give_items", "{\"item\":\"minecraft:stone_axe\",\"count\":1}"),
                        step("build", "{\"item\":\"minecraft:cobblestone\",\"count\":64}"),
                        step("place_block", "{\"item\":\"minecraft:cobblestone\"}"),
                        step("go_to", "{}")),
                STONE_TO_COBBLE);

        assertTrue(needs.isEmpty(), needs.toString());
    }

    @Test
    void brokenStepsDoNotThrowAndReserveNothing() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(
                        step("obtain_item", "{}"),
                        step("obtain_item", "{\"item\":\"\"}"),
                        step("obtain_item", "{\"item\":\"minecraft:oak_log\",\"count\":-3}"),
                        new QueuedStep("mine_blocks", null, null, null, null, null, 1, null),
                        step("mine_blocks", "{\"count\":5}")),
                STONE_TO_COBBLE);

        assertTrue(needs.isEmpty(), needs.toString());
    }

    @Test
    void aBatchStepReservesEveryTarget() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(step(
                        "obtain_items",
                        "{\"items\":[{\"item\":\"minecraft:stone_pickaxe\",\"count\":2},"
                                + "{\"item\":\"minecraft:stone_axe\",\"count\":2}]}")),
                STONE_TO_COBBLE);

        assertEquals(2, needs.get("minecraft:stone_pickaxe"));
        assertEquals(2, needs.get("minecraft:stone_axe"));
    }

    @Test
    void aBatchStepSumsWithOtherSteps() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(
                        step("obtain_item", "{\"item\":\"minecraft:cobblestone\",\"count\":4}"),
                        step("obtain_items", "{\"items\":[{\"item\":\"minecraft:cobblestone\",\"count\":2}]}")),
                STONE_TO_COBBLE);

        assertEquals(6, needs.get("minecraft:cobblestone"));
    }

    @Test
    void aMalformedBatchStepReservesNothing() {
        Map<String, Integer> needs = PlanMaterials.needs(
                List.of(
                        step("obtain_items", "{}"),
                        step("obtain_items", "{\"items\":\"minecraft:stone\"}"),
                        step("obtain_items", "{\"items\":[]}"),
                        step("obtain_items", "{\"items\":[{\"item\":\"\"},{\"count\":4},{}]}"),
                        new QueuedStep("obtain_items", null, null, null, null, null, 1, null)),
                STONE_TO_COBBLE);

        assertTrue(needs.isEmpty(), needs.toString());
    }
}
