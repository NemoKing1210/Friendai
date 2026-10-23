package dev.friedai.crafting.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friedai.crafting.obtain.Source.Hunt;
import dev.friedai.crafting.obtain.Source.Mine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObtainTextTest {
    @Test
    void planTextIronPickaxe() {
        ObtainPlan plan = new ObtainPlan(
                List.of(
                        new ObtainStep.Mine("minecraft:stone", 3, new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)),
                        new ObtainStep.Craft("minecraft:stone_pickaxe", 1),
                        new ObtainStep.Explore("minecraft:iron_ore", true, 16),
                        new ObtainStep.Mine("minecraft:iron_ore", 3, new ToolNeed(ToolKind.PICKAXE, Tier.STONE)),
                        new ObtainStep.Smelt("minecraft:raw_iron", 3, "minecraft:iron_ingot"),
                        new ObtainStep.Craft("minecraft:iron_pickaxe", 1)),
                Map.of(),
                9);
        assertEquals(
                "to get 1 minecraft:iron_pickaxe: mine 3 × minecraft:stone"
                        + " (needs a wooden pickaxe: will craft one), craft minecraft:stone_pickaxe,"
                        + " explore down to Y 16 for minecraft:iron_ore, mine 3 × minecraft:iron_ore,"
                        + " smelt 3 × minecraft:raw_iron, craft minecraft:iron_pickaxe; about 9 steps",
                ObtainText.planText(plan, "minecraft:iron_pickaxe", 1));
    }

    @Test
    void missingTextExploreOff() {
        assertEquals("missing: 1 × minecraft:diamond (exploring is off)",
                ObtainText.missingText(Map.of("minecraft:diamond", 1), true));
        assertEquals("can't get 1 minecraft:diamond here: missing: 1 × minecraft:diamond (exploring is off)",
                "can't get 1 minecraft:diamond here: "
                        + ObtainText.missingText(Map.of("minecraft:diamond", 1), true));
        assertFalse(ObtainText.missingText(Map.of("minecraft:diamond", 1), false).contains("exploring"));
    }

    @Test
    void stepTexts() {
        assertEquals("take 3 × minecraft:iron_ingot", ObtainText.stepText(new ObtainStep.Take("minecraft:iron_ingot", 3)));
        assertEquals("craft minecraft:stone_pickaxe", ObtainText.stepText(new ObtainStep.Craft("minecraft:stone_pickaxe", 1)));
        assertEquals("craft 4 × minecraft:torch", ObtainText.stepText(new ObtainStep.Craft("minecraft:torch", 4)));
        assertEquals("smelt 3 × minecraft:raw_iron", ObtainText.stepText(new ObtainStep.Smelt("minecraft:raw_iron", 3, "minecraft:iron_ingot")));
        assertEquals("mine 3 × minecraft:iron_ore", ObtainText.stepText(new ObtainStep.Mine("minecraft:iron_ore", 3, new ToolNeed(ToolKind.PICKAXE, Tier.STONE))));
        assertEquals("hunt 2 × minecraft:cow", ObtainText.stepText(new ObtainStep.Hunt("minecraft:cow", 2)));
        assertEquals("harvest 4 × minecraft:wheat", ObtainText.stepText(new ObtainStep.Harvest(4, "minecraft:wheat")));
        assertEquals("shear 1 × minecraft:sheep", ObtainText.stepText(new ObtainStep.Shear("minecraft:sheep", 1)));
        assertEquals("milk 1 bucket", ObtainText.stepText(new ObtainStep.Milk(1)));
        assertEquals("milk 2 buckets", ObtainText.stepText(new ObtainStep.Milk(2)));
        assertEquals("explore down to Y 16 for minecraft:iron_ore", ObtainText.stepText(new ObtainStep.Explore("minecraft:iron_ore", true, 16)));
        assertEquals("explore up to 64 blocks around for #minecraft:logs", ObtainText.stepText(new ObtainStep.Explore("#minecraft:logs", false, 0)));
    }

    @Test
    void toolNeedSatisfaction() {
        ToolNeed stone = new ToolNeed(ToolKind.PICKAXE, Tier.STONE);
        assertTrue(stone.satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.IRON)));
        assertFalse(stone.satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)));
        assertFalse(stone.satisfiedBy(new ToolNeed(ToolKind.AXE, Tier.DIAMOND)));
        assertTrue(new ToolNeed(ToolKind.HAND, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.DIAMOND)));
        assertTrue(new ToolNeed(ToolKind.HAND, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.HAND, Tier.NONE)));
        assertTrue(new ToolNeed(ToolKind.PICKAXE, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.AXE, Tier.WOOD)));
    }
}
