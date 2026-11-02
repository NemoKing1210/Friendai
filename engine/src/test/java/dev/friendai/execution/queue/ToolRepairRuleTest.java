package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolRepairRuleTest {
    @Test
    void vanillaPickaxeTiersAreExplicit() {
        var rule = ToolRepairRule.vanillaPickaxes();
        assertEquals("minecraft:wooden_pickaxe", rule.itemFor("minecraft:stone").orElseThrow());
        assertEquals(
                "minecraft:stone_pickaxe", rule.itemFor("minecraft:iron_ore").orElseThrow());
        assertEquals(
                "minecraft:iron_pickaxe", rule.itemFor("minecraft:diamond_ore").orElseThrow());
        assertEquals(
                "minecraft:diamond_pickaxe", rule.itemFor("minecraft:obsidian").orElseThrow());
        assertTrue(rule.itemFor("minecraft:unknown_block").isEmpty());
    }
}
