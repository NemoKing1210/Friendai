package dev.friendai.knowledge.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Evidence that a generated structure is where the companion is: a small set of blocks that only that structure
 * places in quantity. Search never calls a structure locator; seeing a marker is the find. The catalog is data —
 * a new structure is a new entry, and a structure without an entry is simply not searchable yet.
 */
public final class StructureMarkers {
    /** Markers and how many of them must be seen before the structure counts as found. */
    public record Entry(Set<String> markers, int minimum) {
        public Entry {
            markers = Set.copyOf(markers);
            if (markers.isEmpty()) throw new IllegalArgumentException("a structure needs at least one marker");
            if (minimum < 1 || minimum > markers.size()) throw new IllegalArgumentException("invalid marker minimum");
        }
    }

    private static final Map<String, Entry> ENTRIES = build();

    private StructureMarkers() {}

    public static Optional<Entry> get(String structureId) {
        if (structureId == null || structureId.isBlank()) return Optional.empty();
        String id = structureId.contains(":") ? structureId : "minecraft:" + structureId;
        return Optional.ofNullable(ENTRIES.get(id));
    }

    /** Every catalogued structure id, sorted. */
    public static List<String> ids() {
        return ENTRIES.keySet().stream().sorted().toList();
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Set<String> village = new LinkedHashSet<>(List.of("minecraft:bell", "minecraft:composter"));
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            village.add("minecraft:" + color + "_bed");
        }
        entries.put("minecraft:village", new Entry(village, 1));
        entries.put("minecraft:pillager_outpost", new Entry(
                Set.of("minecraft:dark_oak_log", "minecraft:cobblestone"), 1));
        entries.put("minecraft:ocean_monument", new Entry(
                Set.of("minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine",
                        "minecraft:sea_lantern", "minecraft:wet_sponge"), 1));
        entries.put("minecraft:nether_fortress", new Entry(
                Set.of("minecraft:nether_bricks", "minecraft:nether_brick_fence", "minecraft:nether_brick_stairs"), 1));
        entries.put("minecraft:bastion_remnant", new Entry(
                Set.of("minecraft:blackstone", "minecraft:polished_blackstone_bricks",
                        "minecraft:chiseled_polished_blackstone", "minecraft:magma_block"), 1));
        entries.put("minecraft:ancient_city", new Entry(
                Set.of("minecraft:deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:sculk",
                        "minecraft:sculk_catalyst", "minecraft:reinforced_deepslate"), 1));
        entries.put("minecraft:trial_chambers", new Entry(
                Set.of("minecraft:trial_spawner", "minecraft:vault", "minecraft:copper_bulb",
                        "minecraft:chiseled_tuff_bricks"), 1));
        entries.put("minecraft:stronghold", new Entry(
                Set.of("minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks",
                        "minecraft:end_portal_frame", "minecraft:infested_stone_bricks"), 1));
        entries.put("minecraft:woodland_mansion", new Entry(
                Set.of("minecraft:dark_oak_planks", "minecraft:birch_planks", "minecraft:dark_oak_log"), 2));
        entries.put("minecraft:desert_pyramid", new Entry(
                Set.of("minecraft:chiseled_sandstone", "minecraft:orange_terracotta"), 1));
        entries.put("minecraft:jungle_temple", new Entry(
                Set.of("minecraft:mossy_cobblestone", "minecraft:chiseled_stone_bricks"), 1));
        entries.put("minecraft:swamp_hut", new Entry(
                Set.of("minecraft:cauldron", "minecraft:spruce_planks"), 2));
        entries.put("minecraft:ruined_portal", new Entry(
                Set.of("minecraft:obsidian", "minecraft:netherrack", "minecraft:magma_block",
                        "minecraft:crying_obsidian"), 1));
        entries.put("minecraft:mineshaft", new Entry(
                Set.of("minecraft:rail", "minecraft:cobweb", "minecraft:oak_fence"), 1));
        return Map.copyOf(entries);
    }
}
