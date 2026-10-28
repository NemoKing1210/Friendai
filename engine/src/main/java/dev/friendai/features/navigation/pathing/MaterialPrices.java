package dev.friendai.features.navigation.pathing;

import java.util.Set;

/** How much a block is worth as bridge material: cheap dirt-like blocks first, crafted blocks last. */
public final class MaterialPrices {

    private static final Set<String> CHEAP = Set.of(
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:netherrack",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:granite",
            "minecraft:tuff");

    private static final Set<String> NATURAL_TAGS = Set.of(
            "minecraft:dirt",
            "minecraft:base_stone_overworld",
            "minecraft:base_stone_nether",
            "minecraft:terracotta",
            "minecraft:logs",
            "minecraft:sand",
            "minecraft:ice",
            "minecraft:snow");

    private MaterialPrices() {}

    /** Price of using one block of {@code blockId} as material: 1 cheap, 4 other natural, 16 crafted. */
    public static int price(String blockId, Set<String> tags) {
        if (CHEAP.contains(blockId)) {
            return 1;
        }
        for (String tag : tags) {
            if (NATURAL_TAGS.contains(tag)) {
                return 4;
            }
        }
        return 16;
    }
}
