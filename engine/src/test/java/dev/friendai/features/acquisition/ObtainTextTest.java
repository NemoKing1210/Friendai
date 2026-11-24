package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.item.Tier;
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
        assertEquals(
                "missing: 1 × minecraft:diamond (exploring is off)",
                ObtainText.missingText(Map.of("minecraft:diamond", 1), true));
        assertEquals(
                "can't get 1 minecraft:diamond here: missing: 1 × minecraft:diamond (exploring is off)",
                "can't get 1 minecraft:diamond here: " + ObtainText.missingText(Map.of("minecraft:diamond", 1), true));
        assertFalse(
                ObtainText.missingText(Map.of("minecraft:diamond", 1), false).contains("exploring"));
    }

    @Test
    void stepTexts() {
        assertEquals(
                "take 3 × minecraft:iron_ingot", ObtainText.stepText(new ObtainStep.Take("minecraft:iron_ingot", 3)));
        assertEquals(
                "craft minecraft:stone_pickaxe",
                ObtainText.stepText(new ObtainStep.Craft("minecraft:stone_pickaxe", 1)));
        assertEquals("craft 4 × minecraft:torch", ObtainText.stepText(new ObtainStep.Craft("minecraft:torch", 4)));
        assertEquals(
                "smelt 3 × minecraft:raw_iron",
                ObtainText.stepText(new ObtainStep.Smelt("minecraft:raw_iron", 3, "minecraft:iron_ingot")));
        assertEquals(
                "mine 3 × minecraft:iron_ore",
                ObtainText.stepText(
                        new ObtainStep.Mine("minecraft:iron_ore", 3, new ToolNeed(ToolKind.PICKAXE, Tier.STONE))));
        assertEquals(
                "visit remembered site 41 for minecraft:iron_ore",
                ObtainText.stepText(new ObtainStep.VisitResource(41, "minecraft:overworld", "minecraft:iron_ore")));
        assertEquals("hunt 2 × minecraft:cow", ObtainText.stepText(new ObtainStep.Hunt("minecraft:cow", 2)));
        assertEquals("harvest 4 × minecraft:wheat", ObtainText.stepText(new ObtainStep.Harvest(4, "minecraft:wheat")));
        assertEquals("shear 1 × minecraft:sheep", ObtainText.stepText(new ObtainStep.Shear("minecraft:sheep", 1)));
        assertEquals("milk 1 bucket", ObtainText.stepText(new ObtainStep.Milk(1)));
        assertEquals("milk 2 buckets", ObtainText.stepText(new ObtainStep.Milk(2)));
        assertEquals(
                "explore down to Y 16 for minecraft:iron_ore",
                ObtainText.stepText(new ObtainStep.Explore("minecraft:iron_ore", true, 16)));
        assertEquals(
                "explore up to 64 blocks around for #minecraft:logs",
                ObtainText.stepText(new ObtainStep.Explore("#minecraft:logs", false, 0)));
    }

    @Test
    void aPreviewMarksASearchThatIsNotLocatedYetAndNamesTheScope() {
        ObtainPlan plan = new ObtainPlan(
                List.of(
                        new ObtainStep.Explore("#minecraft:logs", false, 0),
                        new ObtainStep.Craft("minecraft:stone_pickaxe", 1)),
                Map.of(),
                2);

        String preview = ObtainText.previewText(plan, "minecraft:stone_pickaxe", 1, Map.of(), "local", 64, 600);

        assertTrue(
                preview.contains("explore up to 64 blocks around for #minecraft:logs (not yet located)"),
                "a speculative search is labeled: " + preview);
        assertTrue(preview.contains("search scope: local (64 blocks, 600 ticks)"), preview);
        assertFalse(
                ObtainText.planText(plan, "minecraft:stone_pickaxe", 1).contains("not yet located"),
                "the plan text used by messages is unchanged");
        assertTrue(ObtainText.previewText(plan, "minecraft:stone_pickaxe", 1, Map.of(), "expedition", 512, 12_000)
                .contains("owner only and only when the server allows expeditions"));
    }

    @Test
    void conditionalPlanExplainsVerificationBoundary() {
        ObtainPlan plan = new ObtainPlan(
                List.of(new ObtainStep.VisitResource(41, "minecraft:overworld", "minecraft:iron_ore")),
                Map.of("minecraft:raw_iron", 2),
                1,
                true);
        assertEquals(
                "to get 2 minecraft:iron_ingot: conditional prefix visit remembered site 41 for minecraft:iron_ore"
                        + "; verify the remembered source, then replan; unresolved: missing: 2 × minecraft:raw_iron",
                ObtainText.planText(plan, "minecraft:iron_ingot", 2));
    }

    @Test
    void aLimitedPlanIsReportedAsAPlanningLimitNotAsAMissingMaterial() {
        ObtainPlan limited = new ObtainPlan(
                List.of(), Map.of("minecraft:diamond", 1), 0, false, new ObtainPlan.Work(20_001, 40_002), true);

        String text = ObtainText.failureText(limited, "minecraft:diamond", 1, true);

        assertTrue(text.contains("work limit"), text);
        assertTrue(text.contains("1 minecraft:diamond"), text);
        assertTrue(text.contains("20001 candidate evaluations"), text);
        assertTrue(text.contains("40002 inventory copies"), text);
        assertFalse(text.contains("can't get"), "a limit is not an absent material: " + text);
        assertFalse(text.contains("missing:"), text);
        assertEquals(text, ObtainText.planText(limited, "minecraft:diamond", 1));
    }

    @Test
    void aFinishedPlanThatCannotBeObtainedKeepsTheMissingLine() {
        ObtainPlan plan =
                new ObtainPlan(List.of(), Map.of("minecraft:diamond", 1), 0, false, new ObtainPlan.Work(3, 4), false);

        assertEquals(
                "can't get 1 minecraft:diamond here: missing: 1 × minecraft:diamond (exploring is off)",
                ObtainText.failureText(plan, "minecraft:diamond", 1, true));
    }

    @Test
    void toolNeedSatisfaction() {
        ToolNeed stone = new ToolNeed(ToolKind.PICKAXE, Tier.STONE);
        assertTrue(stone.satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.IRON)));
        assertFalse(stone.satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)));
        assertFalse(stone.satisfiedBy(new ToolNeed(ToolKind.AXE, Tier.DIAMOND)));
        assertTrue(new ToolNeed(ToolKind.HAND, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.DIAMOND)));
        assertTrue(new ToolNeed(ToolKind.HAND, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.HAND, Tier.NONE)));
        assertFalse(new ToolNeed(ToolKind.PICKAXE, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.AXE, Tier.WOOD)));
        assertTrue(new ToolNeed(ToolKind.PICKAXE, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)));
        assertFalse(new ToolNeed(ToolKind.SHEARS, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.HAND, Tier.NONE)));
        assertTrue(new ToolNeed(ToolKind.SHEARS, Tier.NONE).satisfiedBy(new ToolNeed(ToolKind.SHEARS, Tier.NONE)));
    }
}
