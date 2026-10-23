package dev.friendai.crafting.obtain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test source book: sources and tool mappings are added in order and returned in that order. */
final class FakeSourceBook implements SourceBook {
    private final Map<String, List<Source>> sources = new LinkedHashMap<>();
    private final Map<String, String> toolItems = new LinkedHashMap<>();
    private final Map<String, ToolNeed> tools = new LinkedHashMap<>();

    /** The sources the planner tests reason about, ordered like a vanilla world. */
    static FakeSourceBook vanillaLike() {
        ToolNeed woodenPickaxe = new ToolNeed(ToolKind.PICKAXE, Tier.WOOD);
        ToolNeed stonePickaxe = new ToolNeed(ToolKind.PICKAXE, Tier.STONE);
        return new FakeSourceBook()
                .add("minecraft:oak_log", new Source.Mine("minecraft:oak_log", ToolNeed.HAND, 1, true, 0))
                .add("minecraft:cobblestone", new Source.Mine("minecraft:stone", woodenPickaxe, 1, true, 0))
                .add("minecraft:raw_iron", new Source.Mine("minecraft:iron_ore", stonePickaxe, 1, false, 16))
                .add("minecraft:raw_gold", new Source.Mine("minecraft:gold_ore",
                        new ToolNeed(ToolKind.PICKAXE, Tier.IRON), 1, false, -16))
                .add("minecraft:coal", new Source.Mine("minecraft:coal_ore", woodenPickaxe, 1, false, 96))
                .add("minecraft:leather", new Source.Hunt("minecraft:cow", 1.0))
                .add("minecraft:beef", new Source.Hunt("minecraft:cow", 1.0))
                .add("minecraft:wheat", new Source.Harvest("minecraft:wheat", 1.0))
                .add("minecraft:white_wool", new Source.Shear("minecraft:sheep", 1.0))
                .add("minecraft:milk_bucket", new Source.Milk("minecraft:cow"))
                .tool(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD), "minecraft:wooden_pickaxe")
                .tool(new ToolNeed(ToolKind.PICKAXE, Tier.STONE), "minecraft:stone_pickaxe")
                .tool(new ToolNeed(ToolKind.PICKAXE, Tier.IRON), "minecraft:iron_pickaxe")
                .tool(new ToolNeed(ToolKind.AXE, Tier.WOOD), "minecraft:wooden_axe")
                .tool(new ToolNeed(ToolKind.SHEARS, Tier.NONE), "minecraft:shears")
                .toolOf("minecraft:wooden_pickaxe", new ToolNeed(ToolKind.PICKAXE, Tier.WOOD))
                .toolOf("minecraft:stone_pickaxe", new ToolNeed(ToolKind.PICKAXE, Tier.STONE))
                .toolOf("minecraft:iron_pickaxe", new ToolNeed(ToolKind.PICKAXE, Tier.IRON))
                .toolOf("minecraft:wooden_axe", new ToolNeed(ToolKind.AXE, Tier.WOOD))
                .toolOf("minecraft:shears", new ToolNeed(ToolKind.SHEARS, Tier.NONE));
    }

    FakeSourceBook add(String item, Source source) {
        sources.computeIfAbsent(item, key -> new ArrayList<>()).add(source);
        return this;
    }

    FakeSourceBook tool(ToolNeed need, String item) {
        toolItems.putIfAbsent(need.kind() + "/" + need.tier(), item);
        tools.putIfAbsent(item, need);
        return this;
    }

    FakeSourceBook toolOf(String item, ToolNeed need) {
        tools.put(item, need);
        return this;
    }

    @Override
    public List<Source> sourcesOf(String item) {
        return List.copyOf(sources.getOrDefault(item, List.of()));
    }

    @Override
    public Optional<String> toolItem(ToolNeed need) {
        return Optional.ofNullable(toolItems.get(need.kind() + "/" + need.tier()));
    }

    @Override
    public ToolNeed toolOf(String item) {
        return tools.getOrDefault(item, ToolNeed.HAND);
    }
}
