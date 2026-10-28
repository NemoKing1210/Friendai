package dev.friendai.core;

import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.FarmRef;
import dev.friendai.agent.autonomy.PlaceRef;
import dev.friendai.agent.autonomy.SelfFacts;
import dev.friendai.agent.autonomy.StockKind;
import dev.friendai.agent.autonomy.TradeRef;
import dev.friendai.crafting.Station;
import dev.friendai.crafting.obtain.Tier;
import dev.friendai.crafting.obtain.ToolKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceQuery;
import dev.friendai.place.PlaceService;
import dev.friendai.pathing.Pos;
import dev.friendai.task.cooking.FuelPolicy;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import dev.friendai.task.obtain.DropIndex;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Reads {@link AutonomyFacts} from the world on the server thread, at most once per {@link #CACHE_MILLIS}. */
final class AutonomyFactsBuilder {
    static final long CACHE_MILLIS = 5_000;
    /** Armor points of an iron chestplate and leggings: "two iron pieces" on the ladder. */
    static final int IRON_ARMOR_POINTS = 11;
    static final int DIAMOND_ARMOR_POINTS = 14;
    private static final int MAX_CONTAINERS = 16;
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private record Cached(AutonomyFacts facts, long at) {}

    private final Map<UUID, Cached> cache = new HashMap<>();

    AutonomyFacts build(CompanionEntity companion, UUID owner, long nowMillis) {
        Cached cached = cache.get(owner);
        if (cached != null && nowMillis - cached.at() < CACHE_MILLIS) {
            return cached.facts();
        }
        AutonomyFacts facts = read(companion, nowMillis);
        cache.put(owner, new Cached(facts, nowMillis));
        return facts;
    }

    /** Forget the cached facts, so the next decision sees a change made right now. */
    void invalidate(UUID owner) {
        cache.remove(owner);
    }

    private static AutonomyFacts read(CompanionEntity companion, long nowMillis) {
        ServerLevel level = (ServerLevel) companion.level();
        var bag = companion.inventory();
        DropIndex tools = DropIndex.of(level);
        Map<StockKind, Integer> stock = new EnumMap<>(StockKind.class);
        Map<ToolKind, Tier> best = new EnumMap<>(ToolKind.class);
        int free = 0;
        int food = 0;
        boolean shield = !companion.getOffhandItem().isEmpty() && companion.getOffhandItem().is(Items.SHIELD);
        boolean bucket = false;
        boolean table = false;
        boolean furnace = false;
        boolean fuel = false;
        boolean flintAndSteel = false;
        int obsidian = 0;
        double wear = 1.0;
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(companion.getMainHandItem());
        for (int i = 0; i < bag.getContainerSize(); i++) {
            ItemStack stack = bag.getItem(i);
            if (stack.isEmpty()) {
                free++;
            } else {
                stacks.add(stack);
            }
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            var tool = tools.toolOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            best.merge(tool.kind(), tool.tier(), (a, b) -> b.ordinal() > a.ordinal() ? b : a);
            var properties = stack.get(DataComponents.FOOD);
            if (properties != null) {
                food += properties.nutrition() * stack.getCount();
            }
            stockOf(stack).ifPresent(kind -> stock.merge(kind, stack.getCount(), Integer::sum));
            shield |= stack.is(Items.SHIELD);
            bucket |= stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET);
            table |= stack.is(Items.CRAFTING_TABLE);
            furnace |= stack.is(Items.FURNACE);
            fuel |= FuelPolicy.isFuel(stack);
            flintAndSteel |= stack.is(Items.FLINT_AND_STEEL);
            if (stack.is(Items.OBSIDIAN)) {
                obsidian += stack.getCount();
            }
            wear = Math.min(wear, wear(stack));
        }
        table |= !Stations.nearbyAll(companion, Station.CRAFTING_TABLE, 1).isEmpty();
        furnace |= !Stations.nearbyAll(companion, Station.FURNACE, 1).isEmpty();
        int pieces = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack worn = companion.getItemBySlot(slot);
            if (!worn.isEmpty()) {
                pieces++;
                wear = Math.min(wear, wear(worn));
            }
        }
        int armorPoints = companion.getArmorValue();
        Tier armor = pieces == 0 ? Tier.NONE
                : armorPoints >= DIAMOND_ARMOR_POINTS ? Tier.DIAMOND
                : armorPoints >= IRON_ARMOR_POINTS ? Tier.IRON
                : Tier.WOOD;

        var owner = companion.owner();
        double ownerDistance = owner.map(p -> (double) p.distanceTo(companion)).orElse(Double.MAX_VALUE);
        List<PlaceQuery.Result> homes = places(companion, PlaceKind.HOUSE);
        List<PlaceQuery.Result> storages = places(companion, PlaceKind.STORAGE);
        List<PlaceQuery.Result> farms = places(companion, PlaceKind.FARM);
        List<PlaceQuery.Result> trades = places(companion, PlaceKind.TRADE);
        boolean portalKnown = !places(companion, PlaceKind.PORTAL).isEmpty();
        Optional<PlaceQuery.Result> homeHere = homes.stream().filter(PlaceQuery.Result::sameDimension).findFirst();
        long gameTime = level.getGameTime();
        List<FarmRef> farmRefs = farms.stream()
                .map(r -> new FarmRef(r.place().id(), r.place().name(), r.place().label(), r.distance(),
                        r.sameDimension(),
                        (gameTime - Math.max(r.place().lastVisitTick(), r.place().createdTick())) * 50L))
                .toList();
        List<TradeRef> tradeRefs = trades.stream()
                .map(r -> new TradeRef(r.place().id(), r.place().name(), r.distance(), r.sameDimension(),
                        (gameTime - Math.max(r.place().lastVisitTick(), r.place().createdTick())) * 50L))
                .toList();
        SelfFacts self = new SelfFacts(
                companion.hunger().food(),
                companion.experience().level(),
                wear,
                homeHere.map(r -> hasBlock(level, r.place(), "#minecraft:beds")).orElse(false),
                homeHere.map(r -> hasBlock(level, r.place(), "minecraft:enchanting_table")).orElse(false),
                homeHere.map(r -> hasBlock(level, r.place(), "#minecraft:anvil")).orElse(false),
                flintAndSteel,
                obsidian);
        Optional<Place> unloadAt = storages.stream().filter(PlaceQuery.Result::sameDimension).findFirst()
                .or(() -> homes.stream().filter(PlaceQuery.Result::sameDimension).findFirst())
                .map(PlaceQuery.Result::place);
        int storageFree = unloadAt.map(place -> freeSlots(level, place)).orElse(0);
        int ready = homes.stream().filter(PlaceQuery.Result::sameDimension).findFirst()
                .map(r -> readyFurnaces(level, r.place())).orElse(0);

        return new AutonomyFacts(free, food, Math.round(companion.getHealth()), Math.round(companion.getMaxHealth()),
                stock, best.getOrDefault(ToolKind.PICKAXE, Tier.NONE), best.getOrDefault(ToolKind.AXE, Tier.NONE),
                best.getOrDefault(ToolKind.SWORD, Tier.NONE), armor, pieces, shield, bucket, table, furnace,
                level.isDarkOutside(), companion.getTarget() != null, owner.isPresent(), ownerDistance,
                homeHere.map(AutonomyFactsBuilder::ref), storages.stream().map(AutonomyFactsBuilder::ref).toList(),
                farmRefs, storageFree, ready, fuel, self, tradeRefs, portalKnown, nowMillis);
    }

    /** Remaining durability of one stack, 1 for anything that cannot wear out. */
    private static double wear(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return 1.0;
        }
        return (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage();
    }

    /** Whether a place contains a block matching the id or #tag, within a small radius of its anchor. */
    private static boolean hasBlock(ServerLevel level, Place place, String idOrTag) {
        Optional<BlockMatcher> matcher = BlockMatcher.parse(idOrTag);
        if (matcher.isEmpty()) {
            return false;
        }
        int radius = Math.min(place.radius(), 12);
        return !BlockSearch.nearestN(level, anchor(place), radius, matcher.get(), Set.of(), 1).isEmpty();
    }

    private static Optional<StockKind> stockOf(ItemStack stack) {
        if (stack.is(ItemTags.LOGS)) return Optional.of(StockKind.LOGS);
        if (stack.is(ItemTags.PLANKS)) return Optional.of(StockKind.PLANKS);
        if (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)) return Optional.of(StockKind.COBBLE);
        if (stack.is(ItemTags.COALS)) return Optional.of(StockKind.COAL);
        if (stack.is(Items.RAW_IRON) || stack.is(Items.RAW_GOLD) || stack.is(Items.RAW_COPPER)) {
            return Optional.of(StockKind.RAW_ORE);
        }
        if (stack.is(Items.IRON_INGOT)) return Optional.of(StockKind.IRON_INGOT);
        if (stack.is(Items.DIAMOND)) return Optional.of(StockKind.DIAMOND);
        if (stack.is(Items.TORCH)) return Optional.of(StockKind.TORCHES);
        if (stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)) return Optional.of(StockKind.SEEDS);
        if (stack.is(Items.BONE_MEAL)) return Optional.of(StockKind.BONEMEAL);
        return Optional.empty();
    }

    private static List<PlaceQuery.Result> places(CompanionEntity companion, PlaceKind kind) {
        return PlaceService.find(companion, new PlaceQuery.Request(Optional.empty(), Set.of(kind), Optional.empty(),
                PlaceQuery.LIMIT_MAX, true));
    }

    private static PlaceRef ref(PlaceQuery.Result r) {
        return new PlaceRef(r.place().id(), r.place().name(), r.distance(), r.sameDimension());
    }

    /** Empty slots in the storage containers inside the place's box, in loaded chunks. */
    private static int freeSlots(ServerLevel level, Place place) {
        int free = 0;
        for (BlockPos pos : containersIn(level, place)) {
            Optional<Container> container = ContainerAccess.at(level, pos);
            if (container.isEmpty()) {
                continue;
            }
            for (int i = 0; i < container.get().getContainerSize(); i++) {
                if (container.get().getItem(i).isEmpty()) {
                    free++;
                }
            }
        }
        return free;
    }

    static List<BlockPos> containersIn(ServerLevel level, Place place) {
        BlockPos anchor = anchor(place);
        int r = place.radius();
        return ContainerAccess.all(level, anchor, r * 2).stream()
                .filter(pos -> place.contains(new Pos(pos.getX(), pos.getY(), pos.getZ())))
                .limit(MAX_CONTAINERS)
                .toList();
    }

    /** Furnaces inside the place's box whose result slot holds something. */
    private static int readyFurnaces(ServerLevel level, Place place) {
        BlockPos anchor = anchor(place);
        int r = place.radius();
        int ready = 0;
        for (int cx = (anchor.getX() - r) >> 4; cx <= (anchor.getX() + r) >> 4; cx++) {
            for (int cz = (anchor.getZ() - r) >> 4; cz <= (anchor.getZ() + r) >> 4; cz++) {
                BlockPos sample = new BlockPos(cx << 4, anchor.getY(), cz << 4);
                if (!level.hasChunkAt(sample)) {
                    continue;
                }
                for (BlockEntity entity : level.getChunkAt(sample).getBlockEntities().values()) {
                    if (entity instanceof AbstractFurnaceBlockEntity furnace && place.contains(new Pos(entity.getBlockPos().getX(), entity.getBlockPos().getY(), entity.getBlockPos().getZ()))
                            && !furnace.getItem(2).isEmpty()) {
                        ready++;
                    }
                }
            }
        }
        return ready;
    }

    static BlockPos anchor(Place place) {
        return new BlockPos(place.anchor().x(), place.anchor().y(), place.anchor().z());
    }

}
