package dev.friendai.agent.autonomy;

import java.util.Optional;

/** The bag contents free time counts, each with the words the model reads and the item it can ask for. */
public enum StockKind {
    LOGS("logs", ""),
    PLANKS("planks", "minecraft:oak_planks"),
    COBBLE("cobblestone", "minecraft:cobblestone"),
    COAL("coal", "minecraft:coal"),
    RAW_ORE("raw ore", ""),
    IRON_INGOT("iron ingots", "minecraft:iron_ingot"),
    DIAMOND("diamonds", "minecraft:diamond"),
    TORCHES("torches", "minecraft:torch"),
    SEEDS("seeds", "minecraft:wheat_seeds"),
    BONEMEAL("bone meal", "minecraft:bone_meal");

    private final String words;
    private final String itemId;

    StockKind(String words, String itemId) {
        this.words = words;
        this.itemId = itemId;
    }

    public String words() {
        return words;
    }

    /**
     * The item id an {@code obtain_item} step asks for, or empty when one id cannot stand for the stock: logs come
     * from any tree, and raw ore is several items.
     */
    public Optional<String> itemId() {
        return itemId.isEmpty() ? Optional.empty() : Optional.of(itemId);
    }
}
