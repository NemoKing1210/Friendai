package dev.friendai.features.acquisition.minecraft.search;

import com.mojang.authlib.GameProfile;
import dev.friendai.features.acquisition.OreDepths;
import dev.friendai.features.acquisition.Source;
import dev.friendai.features.acquisition.SourceBook;
import dev.friendai.features.acquisition.ToolKind;
import dev.friendai.features.acquisition.ToolNeed;
import dev.friendai.foundation.item.Tier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What blocks and mobs drop, learned by probing them once per server: every block's default state is evaluated
 * with reference tools, and every creature's default loot table is rolled, so datapacks and mods are covered
 * without a hand-written table. The result is the planner's {@link SourceBook}.
 */
public final class DropIndex implements SourceBook {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/obtain");
    private static final int ROLLS = 5;
    private static final int PROBE_X = 0;
    private static final int PROBE_Z = 0;
    /** A datapack reload replaces the manager, so the cached index follows the key. */
    private static final Map<RecipeManager, DropIndex> CACHE = new WeakHashMap<>();

    private static final Set<MobCategory> MOB_CATEGORIES =
            EnumSet.of(MobCategory.CREATURE, MobCategory.MONSTER, MobCategory.WATER_CREATURE);
    private static final Map<ToolNeed, String> TOOL_ITEMS = toolItems();
    private static final Map<String, ToolNeed> VANILLA_TOOLS = vanillaTools();
    private static final List<ReferenceTool> REFERENCE_TOOLS = List.of(
            new ReferenceTool(new ItemStack(Items.AIR), ToolNeed.HAND),
            new ReferenceTool(new ItemStack(Items.WOODEN_PICKAXE), new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)),
            new ReferenceTool(new ItemStack(Items.STONE_PICKAXE), new ToolNeed(ToolKind.PICKAXE, Tier.STONE)),
            new ReferenceTool(new ItemStack(Items.IRON_PICKAXE), new ToolNeed(ToolKind.PICKAXE, Tier.IRON)),
            new ReferenceTool(new ItemStack(Items.DIAMOND_PICKAXE), new ToolNeed(ToolKind.PICKAXE, Tier.DIAMOND)),
            new ReferenceTool(new ItemStack(Items.WOODEN_AXE), new ToolNeed(ToolKind.AXE, Tier.WOOD)),
            new ReferenceTool(new ItemStack(Items.WOODEN_SHOVEL), new ToolNeed(ToolKind.SHOVEL, Tier.WOOD)),
            new ReferenceTool(new ItemStack(Items.WOODEN_HOE), new ToolNeed(ToolKind.HOE, Tier.WOOD)),
            new ReferenceTool(new ItemStack(Items.SHEARS), new ToolNeed(ToolKind.SHEARS, Tier.NONE)));
    private static final Map<String, String> CROPS = Map.of(
            "minecraft:wheat", "minecraft:wheat",
            "minecraft:carrots", "minecraft:carrot",
            "minecraft:potatoes", "minecraft:potato",
            "minecraft:beetroots", "minecraft:beetroot",
            "minecraft:torchflower_crop", "minecraft:torchflower",
            "minecraft:pitcher_crop", "minecraft:pitcher_plant",
            "minecraft:nether_wart", "minecraft:nether_wart");
    private final Map<String, List<Source>> byOutput;

    private DropIndex(Map<String, List<Source>> byOutput) {
        this.byOutput = byOutput;
    }
    /** The index for the level's current recipe manager, built once per manager on the server thread. */
    public static synchronized DropIndex of(ServerLevel level) {
        RecipeManager manager = level.recipeAccess();
        DropIndex cached = CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        DropIndex built = build(level);
        CACHE.put(manager, built);
        return built;
    }

    @Override
    public List<Source> sourcesOf(String item) {
        return byOutput.getOrDefault(item, List.of());
    }

    @Override
    public Optional<String> toolItem(ToolNeed need) {
        String item = TOOL_ITEMS.get(need);
        if (item == null && need.tier() == Tier.NONE && need.kind() != ToolKind.HAND) {
            item = need.vanillaItem();
        }
        return Optional.ofNullable(item);
    }

    @Override
    public ToolNeed toolOf(String item) {
        ToolNeed known = VANILLA_TOOLS.get(item);
        if (known != null) {
            return known;
        }
        Identifier id = Identifier.tryParse(item);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ToolNeed.HAND;
        }
        Item value = BuiltInRegistries.ITEM.getValue(id);
        var holder = value.builtInRegistryHolder();
        if (holder.is(ItemTags.PICKAXES)) {
            return new ToolNeed(ToolKind.PICKAXE, Tier.IRON);
        }
        if (holder.is(ItemTags.AXES)) {
            return new ToolNeed(ToolKind.AXE, Tier.IRON);
        }
        if (holder.is(ItemTags.SHOVELS)) {
            return new ToolNeed(ToolKind.SHOVEL, Tier.IRON);
        }
        if (holder.is(ItemTags.HOES)) {
            return new ToolNeed(ToolKind.HOE, Tier.IRON);
        }
        if (holder.is(ItemTags.SWORDS)) {
            return new ToolNeed(ToolKind.SWORD, Tier.IRON);
        }
        return ToolNeed.HAND;
    }

    private static DropIndex build(ServerLevel level) {
        long started = System.nanoTime();
        Map<String, List<Source>> sources = new LinkedHashMap<>();
        BlockPos probe = new BlockPos(PROBE_X, level.getMinY(), PROBE_Z);
        int blocks = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (state.isAir() || state.liquid() || state.getDestroySpeed(level, probe) < 0) {
                continue;
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            Map<String, Drop> drops = probeBlock(level, probe, state);
            if (drops.isEmpty()) {
                continue;
            }
            boolean surface = isSurface(state);
            for (Map.Entry<String, Drop> entry : drops.entrySet()) {
                Drop drop = entry.getValue();
                int depthY = state.is(BlockTags.ORES)
                        ? OreDepths.depthFor(entry.getKey()).orElse(0)
                        : 0;
                sources.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(new Source.Mine(blockId, drop.tool, drop.perBlock, surface, depthY));
            }
            blocks++;
        }

        FakePlayer player = fakePlayer(level);
        int mobs = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Optional<net.minecraft.resources.ResourceKey<LootTable>> tableKey = type.getDefaultLootTable();
            if (tableKey.isEmpty() || !MOB_CATEGORIES.contains(type.getCategory())) {
                continue;
            }
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            Map<String, Double> loot = probeMob(level, type, tableKey.get(), player);
            if (loot.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Double> entry : loot.entrySet()) {
                sources.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(new Source.Hunt(typeId, entry.getValue()));
            }
            mobs++;
        }

        for (Map.Entry<String, String> crop : CROPS.entrySet()) {
            sources.computeIfAbsent(crop.getValue(), key -> new ArrayList<>())
                    .add(new Source.Harvest(crop.getKey(), 1.0));
        }
        sources.computeIfAbsent("minecraft:white_wool", key -> new ArrayList<>())
                .add(new Source.Shear("minecraft:sheep", 1.0));
        sources.computeIfAbsent("minecraft:milk_bucket", key -> new ArrayList<>())
                .add(new Source.Milk("minecraft:cow"));
        sources.computeIfAbsent("minecraft:milk_bucket", key -> new ArrayList<>())
                .add(new Source.Milk("minecraft:goat"));

        Map<String, List<Source>> frozen = new LinkedHashMap<>();
        sources.forEach((item, list) -> frozen.put(item, List.copyOf(list)));
        long millis = (System.nanoTime() - started) / 1_000_000L;
        LOGGER.info("Indexed drops for {} blocks and {} mobs in {} ms", blocks, mobs, millis);
        return new DropIndex(Map.copyOf(frozen));
    }

    private static Map<String, Drop> probeBlock(ServerLevel level, BlockPos pos, BlockState state) {
        Map<String, Drop> drops = new LinkedHashMap<>();
        for (ReferenceTool tool : REFERENCE_TOOLS) {
            if (state.requiresCorrectToolForDrops() && !tool.stack.isCorrectToolForDrops(state)) {
                continue;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int roll = 0; roll < ROLLS; roll++) {
                try {
                    for (ItemStack stack : Block.getDrops(state, level, pos, null, null, tool.stack)) {
                        if (!stack.isEmpty()) {
                            counts.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
                        }
                    }
                } catch (RuntimeException e) {
                    return Map.of();
                }
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (!drops.containsKey(entry.getKey())) {
                    double perBlock = entry.getValue() / (double) ROLLS;
                    if (perBlock > 0) {
                        drops.put(entry.getKey(), new Drop(tool.need, perBlock));
                    }
                }
            }
        }
        return drops;
    }

    private static Map<String, Double> probeMob(
            ServerLevel level,
            EntityType<?> type,
            net.minecraft.resources.ResourceKey<LootTable> tableKey,
            FakePlayer player) {
        Entity entity;
        try {
            entity = type.create(level, EntitySpawnReason.LOAD);
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (entity == null) {
            return Map.of();
        }
        LootTable table;
        try {
            table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        } catch (RuntimeException e) {
            return Map.of();
        }
        Vec3 origin = Vec3.atCenterOf(new BlockPos(PROBE_X, level.getMinY() + 1, PROBE_Z));
        DamageSource damage = level.damageSources().playerAttack(player);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int roll = 0; roll < ROLLS; roll++) {
            try {
                LootParams params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, entity)
                        .withParameter(LootContextParams.ORIGIN, origin)
                        .withParameter(LootContextParams.DAMAGE_SOURCE, damage)
                        .withParameter(LootContextParams.ATTACKING_ENTITY, player)
                        .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
                        .create(LootContextParamSets.ENTITY);
                for (ItemStack stack : table.getRandomItems(params)) {
                    if (!stack.isEmpty()) {
                        counts.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
                    }
                }
            } catch (RuntimeException e) {
                return Map.of();
            }
        }
        Map<String, Double> perKill = new LinkedHashMap<>();
        counts.forEach((item, total) -> {
            double average = total / (double) ROLLS;
            if (average > 0) {
                perKill.put(item, average);
            }
        });
        return perKill;
    }

    private static FakePlayer fakePlayer(ServerLevel level) {
        UUID uuid = UUID.nameUUIDFromBytes("friendai-drop-index".getBytes(StandardCharsets.UTF_8));
        return FakePlayer.get(level, new GameProfile(uuid, "FriendaiIndex"));
    }

    private static boolean isSurface(BlockState state) {
        if (state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.DIRT)) {
            return true;
        }
        Block block = state.getBlock();
        return block == Blocks.GRAVEL
                || block == Blocks.CLAY
                || block == Blocks.STONE
                || block == Blocks.COBBLESTONE
                || block == Blocks.ANDESITE
                || block == Blocks.DIORITE
                || block == Blocks.GRANITE;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Map<ToolNeed, String> toolItems() {
        Map<ToolNeed, String> items = new LinkedHashMap<>();
        tool(items, ToolKind.PICKAXE, Tier.WOOD, "minecraft:wooden_pickaxe");
        tool(items, ToolKind.PICKAXE, Tier.STONE, "minecraft:stone_pickaxe");
        tool(items, ToolKind.PICKAXE, Tier.IRON, "minecraft:iron_pickaxe");
        tool(items, ToolKind.PICKAXE, Tier.DIAMOND, "minecraft:diamond_pickaxe");
        tool(items, ToolKind.PICKAXE, Tier.NETHERITE, "minecraft:netherite_pickaxe");
        tool(items, ToolKind.AXE, Tier.WOOD, "minecraft:wooden_axe");
        tool(items, ToolKind.AXE, Tier.STONE, "minecraft:stone_axe");
        tool(items, ToolKind.AXE, Tier.IRON, "minecraft:iron_axe");
        tool(items, ToolKind.AXE, Tier.DIAMOND, "minecraft:diamond_axe");
        tool(items, ToolKind.AXE, Tier.NETHERITE, "minecraft:netherite_axe");
        tool(items, ToolKind.SHOVEL, Tier.WOOD, "minecraft:wooden_shovel");
        tool(items, ToolKind.SHOVEL, Tier.STONE, "minecraft:stone_shovel");
        tool(items, ToolKind.SHOVEL, Tier.IRON, "minecraft:iron_shovel");
        tool(items, ToolKind.SHOVEL, Tier.DIAMOND, "minecraft:diamond_shovel");
        tool(items, ToolKind.SHOVEL, Tier.NETHERITE, "minecraft:netherite_shovel");
        tool(items, ToolKind.HOE, Tier.WOOD, "minecraft:wooden_hoe");
        tool(items, ToolKind.HOE, Tier.STONE, "minecraft:stone_hoe");
        tool(items, ToolKind.HOE, Tier.IRON, "minecraft:iron_hoe");
        tool(items, ToolKind.HOE, Tier.DIAMOND, "minecraft:diamond_hoe");
        tool(items, ToolKind.HOE, Tier.NETHERITE, "minecraft:netherite_hoe");
        tool(items, ToolKind.SWORD, Tier.WOOD, "minecraft:wooden_sword");
        tool(items, ToolKind.SWORD, Tier.STONE, "minecraft:stone_sword");
        tool(items, ToolKind.SWORD, Tier.IRON, "minecraft:iron_sword");
        tool(items, ToolKind.SWORD, Tier.DIAMOND, "minecraft:diamond_sword");
        tool(items, ToolKind.SWORD, Tier.NETHERITE, "minecraft:netherite_sword");
        tool(items, ToolKind.SHEARS, Tier.NONE, "minecraft:shears");
        return Map.copyOf(items);
    }

    private static void tool(Map<ToolNeed, String> items, ToolKind kind, Tier tier, String item) {
        items.put(new ToolNeed(kind, tier), item);
    }

    private static Map<String, ToolNeed> vanillaTools() {
        Map<String, ToolNeed> tools = new LinkedHashMap<>();
        TOOL_ITEMS.forEach((need, item) -> tools.put(item, need));
        return Map.copyOf(tools);
    }

    private record ReferenceTool(ItemStack stack, ToolNeed need) {}

    private record Drop(ToolNeed tool, double perBlock) {}
}
