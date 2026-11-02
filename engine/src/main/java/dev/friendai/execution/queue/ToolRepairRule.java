package dev.friendai.execution.queue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The smallest carried tool that can harvest a known block. This is a bounded, explicit table of vanilla pickaxe
 * requirements, not a universal material ordering: an unknown block has no rule and the caller must refuse.
 */
public final class ToolRepairRule {
    private final Map<String, String> items;

    private ToolRepairRule(Map<String, String> items) {
        this.items = Map.copyOf(items);
    }

    public static ToolRepairRule vanillaPickaxes() {
        Map<String, String> items = new LinkedHashMap<>();
        for (String block : new String[] {
            "minecraft:stone",
            "minecraft:cobblestone",
            "minecraft:coal_ore",
            "minecraft:deepslate_coal_ore",
            "minecraft:netherrack",
            "minecraft:blackstone"
        }) {
            items.put(block, "minecraft:wooden_pickaxe");
        }
        for (String block : new String[] {
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:copper_ore",
            "minecraft:deepslate_copper_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"
        }) {
            items.put(block, "minecraft:stone_pickaxe");
        }
        for (String block : new String[] {
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:redstone_ore",
            "minecraft:deepslate_redstone_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"
        }) {
            items.put(block, "minecraft:iron_pickaxe");
        }
        items.put("minecraft:obsidian", "minecraft:diamond_pickaxe");
        items.put("minecraft:ancient_debris", "minecraft:diamond_pickaxe");
        return new ToolRepairRule(items);
    }

    public Optional<String> itemFor(String block) {
        return Optional.ofNullable(items.get(block));
    }

    public int size() {
        return items.size();
    }
}
