package dev.friedai.crafting;

import static dev.friedai.crafting.Ingredient.of;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftTextTest {
    @Test
    void missingText() {
        var m = new LinkedHashMap<String, Integer>();
        m.put("#minecraft:logs", 3);
        m.put("minecraft:iron_ingot", 2);
        assertEquals("missing: 3 × #minecraft:logs, 2 × minecraft:iron_ingot", CraftText.missingText(m));
    }

    @Test
    void recipeTextGroupsIngredients() {
        Recipe r = new Recipe("p", "minecraft:iron_pickaxe", 1,
                List.of(of("minecraft:iron_ingot"), of("minecraft:iron_ingot"),
                        of("minecraft:iron_ingot"), of("minecraft:stick"), of("minecraft:stick")),
                Station.CRAFTING_TABLE);
        assertEquals(
                "minecraft:iron_pickaxe ×1 at a crafting table from 3 × minecraft:iron_ingot, 2 × minecraft:stick",
                CraftText.recipeText(r));
    }

    @Test
    void planTextCanCraft() {
        Recipe sticks = new Recipe("stick", "minecraft:stick", 4,
                List.of(of("minecraft:oak_planks"), of("minecraft:oak_planks")), Station.INVENTORY);
        Recipe pickaxe = new Recipe("pick", "minecraft:iron_pickaxe", 1,
                List.of(of("minecraft:iron_ingot"), of("minecraft:iron_ingot"),
                        of("minecraft:iron_ingot"), of("minecraft:stick"), of("minecraft:stick")),
                Station.CRAFTING_TABLE);
        CraftPlan plan = new CraftPlan(
                List.of(new CraftStep(sticks, 1), new CraftStep(pickaxe, 1)),
                Map.of(),
                Set.of(Station.INVENTORY, Station.CRAFTING_TABLE),
                Set.of());
        assertEquals(
                "can craft 1 minecraft:iron_pickaxe now: craft minecraft:stick (1×) in the inventory, "
                        + "then minecraft:iron_pickaxe (1×) at a crafting table",
                CraftText.planText(plan, "minecraft:iron_pickaxe", 1));
    }

    @Test
    void planTextMissing() {
        CraftPlan missingPlan = new CraftPlan(
                List.of(), Map.of("minecraft:iron_ingot", 2), Set.of(), Set.of());
        assertEquals("can't craft 1 minecraft:iron_pickaxe yet; missing: 2 × minecraft:iron_ingot",
                CraftText.planText(missingPlan, "minecraft:iron_pickaxe", 1));
    }
}
