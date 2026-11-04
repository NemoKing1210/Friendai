package dev.friendai.task.queue;

import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.GuardFacts;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.BlockSearch;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** One server-thread facts pass for queue guards. */
public final class CompanionGuardFacts implements GuardFacts {
    private final CompanionEntity companion;
    private final Map<String, Integer> itemCounts = new HashMap<>();
    private final Map<String, Integer> ownerItemCounts = new HashMap<>();
    private final Map<String, Boolean> blockFacts = new HashMap<>();
    private Integer emptySlots, ownerDistance;
    private Float healthFraction;
    private String dimension;
    private double totalDistance;
    private double lastX = Double.NaN, lastZ = Double.NaN;
    private long lastSampleTick = Long.MIN_VALUE;
    private long planStartTick;
    private long planStartDistance;
    private long elapsedTicks;
    private int travelledBlocks;
    private long blockStamp = Long.MIN_VALUE;
    private static final long BLOCK_TTL_TICKS = 40;

    public CompanionGuardFacts(CompanionEntity companion) {
        this.companion = companion;
    }
    /** Starts a new pass: cheap facts are re-read, the expensive block scans are reused for a short while. */
    public void refresh() {
        refresh(0, 0);
    }
    /** Refreshes a pass and exposes distance/time relative to one stored plan baseline. */
    public void refresh(long startTick, long startDistance) {
        itemCounts.clear();
        ownerItemCounts.clear();
        emptySlots = null;
        ownerDistance = null;
        healthFraction = null;
        dimension = null;
        long now = companion.level().getGameTime();
        planStartTick = Math.max(0, startTick);
        planStartDistance = Math.max(0, startDistance);
        if (now != lastSampleTick) {
            double x = companion.getX(), z = companion.getZ();
            if (!Double.isNaN(lastX)) totalDistance += Math.hypot(x - lastX, z - lastZ);
            lastX = x;
            lastZ = z;
            lastSampleTick = now;
        }
        elapsedTicks = Math.max(0, now - planStartTick);
        travelledBlocks =
                (int) Math.clamp((long) Math.floor(totalDistance - planStartDistance / 1000.0), 0, Integer.MAX_VALUE);
        if (now - blockStamp >= BLOCK_TTL_TICKS || now < blockStamp) {
            blockFacts.clear();
            blockStamp = now;
        }
    }
    /** Cumulative horizontal distance in milli-blocks, retaining a plan's fractional baseline. */
    public long totalTravelledDistance() {
        return Math.round(totalDistance * 1000.0);
    }

    @Override
    public int distanceTravelled() {
        return travelledBlocks;
    }

    @Override
    public long timeElapsed() {
        return elapsedTicks;
    }

    @Override
    public int countItems(String id) {
        if (itemCounts.containsKey(id)) return itemCounts.get(id);
        var matcher = ItemMatcher.parse(id).orElse(null);
        if (matcher == null) return 0;
        int count = matcher.test(companion.getMainHandItem())
                ? companion.getMainHandItem().getCount()
                : 0;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (matcher.test(stack)) count += stack.getCount();
        }
        itemCounts.put(id, count);
        return count;
    }

    @Override
    public int ownerCountItems(String id) {
        if (ownerItemCounts.containsKey(id)) return ownerItemCounts.get(id);
        ServerPlayer owner = companion.owner().orElse(null);
        var matcher = owner == null ? null : ItemMatcher.parse(id).orElse(null);
        if (matcher == null) return 0;
        var inventory = owner.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (matcher.test(stack)) count += stack.getCount();
        }
        // A drop the companion handed over at the owner's feet is evidence too, but only while it is still there:
        // the owner's pickup is confirmation, and a despawned or destroyed stack proves nothing.
        count = Math.max(count, delivered(matcher));
        ownerItemCounts.put(id, count);
        return count;
    }

    /** What the companion handed over recently and whose drop is still in the world. */
    private int delivered(ItemMatcher matcher) {
        if (companion.delivered().isEmpty()) {
            return 0;
        }
        return companion
                .delivered()
                .count(
                        matcher.label(),
                        companion.level().getGameTime(),
                        uuid -> companion.level().getEntity(uuid) instanceof ItemEntity item
                                && !item.isRemoved()
                                && matcher.test(item.getItem()));
    }

    @Override
    public int emptySlots() {
        if (emptySlots == null) {
            int n = 0;
            for (int i = 0; i < companion.inventory().getContainerSize(); i++)
                if (companion.inventory().getItem(i).isEmpty()) n++;
            emptySlots = n;
        }
        return emptySlots;
    }

    @Override
    public float healthFraction() {
        if (healthFraction == null) healthFraction = companion.getHealth() / Math.max(1f, companion.getMaxHealth());
        return healthFraction;
    }

    @Override
    public int foodLevel() {
        return companion.syncedFood();
    }

    @Override
    public int ownerDistance() {
        if (ownerDistance == null) {
            ServerPlayer owner = companion.owner().orElse(null);
            ownerDistance = owner == null || owner.level() != companion.level()
                    ? -1
                    : (int) Math.round(Math.sqrt(companion.distanceToSqr(owner)));
        }
        return ownerDistance;
    }

    @Override
    public int countBlocks(String id, int radius) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null) return 0;
        int n = 0;
        BlockPos p = companion.blockPosition();
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    BlockPos at = p.offset(x, y, z);
                    if (companion.level().hasChunkAt(at)
                            && matcher.test(companion.level().getBlockState(at))) n++;
                }
        return n;
    }

    @Override
    public String dimension() {
        if (dimension == null)
            dimension = companion.level().dimension().identifier().toString();
        return dimension;
    }

    @Override
    public boolean blockInFront(String id, int distance) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null || distance < 1 || distance > 8) return false;
        BlockPos feet = companion.blockPosition().relative(companion.getDirection(), distance);
        if (!companion.level().hasChunkAt(feet)) return false;
        return matcher.test(companion.level().getBlockState(feet))
                || (companion.level().hasChunkAt(feet.above())
                        && matcher.test(companion.level().getBlockState(feet.above())));
    }

    @Override
    public boolean blockAt(String id, int x, int y, int z) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null) return false;
        BlockPos pos = new BlockPos(x, y, z);
        if (!companion.level().hasChunkAt(pos)) return false;
        return matcher.test(companion.level().getBlockState(pos));
    }

    @Override
    public String biome() {
        BlockPos feet = companion.blockPosition();
        if (!companion.level().hasChunkAt(feet)) return "";
        return companion
                .level()
                .getBiome(feet)
                .unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("");
    }

    @Override
    public boolean fluidAhead() {
        BlockPos feet = companion.blockPosition().relative(companion.getDirection());
        if (!companion.level().hasChunkAt(feet)) return false;
        return !companion.level().getBlockState(feet).getFluidState().isEmpty()
                || (companion.level().hasChunkAt(feet.above())
                        && !companion
                                .level()
                                .getBlockState(feet.above())
                                .getFluidState()
                                .isEmpty());
    }

    @Override
    public Boolean ownerOnline() {
        return companion.owner().isPresent();
    }

    @Override
    public Boolean daytime() {
        // The vanilla 0..24000 clock; day starts at sunrise and night at sunset.
        return Math.floorMod(companion.level().getDefaultClockTime(), 24_000L) < 12_000L;
    }

    @Override
    public String weather() {
        return companion.level().isThundering() ? "thunder" : companion.level().isRaining() ? "rain" : "clear";
    }

    @Override
    public int xpLevel() {
        return companion.syncedXpLevel();
    }

    @Override
    public Boolean stationWithin(String station, int radius) {
        BlockMatcher matcher = BlockMatcher.parse(station).orElse(null);
        if (matcher == null || !(companion.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        return BlockSearch.nearest(level, companion.blockPosition(), radius, matcher, Set.of())
                .isPresent();
    }

    @Override
    public Boolean canHarvest(String block) {
        Identifier id = Identifier.tryParse(block);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        var state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if (companion.getMainHandItem().isCorrectToolForDrops(state)) {
            return true;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Boolean planDone(long planId) {
        var queue = companion.executionCoordinator().queue();
        if (queue.plan(planId).isEmpty()) {
            return null;
        }
        return queue.planStatus(planId) == PlanStatus.DONE;
    }

    @Override
    public Boolean goalMet(long goalId) {
        if (companion.ownerUuid() == null) {
            return null;
        }
        ServerRuntime runtime =
                ServerRuntimeRegistry.find(companion.level().getServer()).orElse(null);
        if (runtime == null) {
            return null;
        }
        return runtime.goals().board(companion.ownerUuid()).all().stream()
                .filter(goal -> goal.id() == goalId)
                .findFirst()
                .map(goal -> goal.status() == GoalStatus.DONE)
                .orElse(null);
    }

    @Override
    public boolean hasBlocks(String id, int radius, int count) {
        String key = id + ":" + radius + ":" + count;
        if (blockFacts.containsKey(key)) return blockFacts.get(key);
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null) return false;
        int found = 0;
        BlockPos p = companion.blockPosition();
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    BlockPos at = p.offset(x, y, z);
                    if (companion.level().hasChunkAt(at)
                            && matcher.test(companion.level().getBlockState(at))
                            && ++found >= count) {
                        blockFacts.put(key, true);
                        return true;
                    }
                }
        blockFacts.put(key, false);
        return false;
    }
}
