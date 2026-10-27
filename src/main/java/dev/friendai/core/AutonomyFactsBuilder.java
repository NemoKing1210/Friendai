package dev.friendai.core;

import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.FarmRef;
import dev.friendai.agent.autonomy.PlaceRef;
import dev.friendai.agent.autonomy.StockKind;
import dev.friendai.crafting.Station;
import dev.friendai.crafting.obtain.Tier;
import dev.friendai.crafting.obtain.ToolKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceQuery;
import dev.friendai.place.PlaceService;
import dev.friendai.task.cooking.FuelPolicy;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.interact.ContainerAccess;
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
        }
        table |= !Stations.nearbyAll(companion, Station.CRAFTING_TABLE, 1).isEmpty();
        furnace |= !Stations.nearbyAll(companion, Station.FURNACE, 1).isEmpty();
        int pieces = 0;
        for (EquipmentSlot slot : ARMOR) {
            if (!companion.getItemBySlot(slot).isEmpty()) {
                pieces++;
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
        long gameTime = level.getGameTime();
        List<FarmRef> farmRefs = farms.stream()
                .map(r -> new FarmRef(r.place().id(), r.place().name(), r.place().label(), r.distance(),
                        r.sameDimension(),
                        (gameTime - Math.max(r.place().lastVisitTick(), r.place().createdTick())) * 50L))
                .toList();
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
                homes.stream().findFirst().map(AutonomyFactsBuilder::ref), storages.stream().map(AutonomyFactsBuilder::ref).toList(),
                farmRefs, storageFree, ready, fuel, nowMillis);
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
                .filter(pos -> inside(anchor, r, pos))
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
                    if (entity instanceof AbstractFurnaceBlockEntity furnace && inside(anchor, r, entity.getBlockPos())
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

    static boolean inside(BlockPos anchor, int r, BlockPos pos) {
        return Math.abs(pos.getX() - anchor.getX()) <= r && Math.abs(pos.getY() - anchor.getY()) <= r
                && Math.abs(pos.getZ() - anchor.getZ()) <= r;
    }
}
