package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Fishes at nearby open water with a fishing rod. */
public final class FishTask implements Task {
    private static final int RADIUS = 16;

    private final int count;
    private final @Nullable IntSupplier waitSource;
    private final Map<String, Integer> caught = new LinkedHashMap<>();

    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private BlockPos water;
    private Approach approach;
    private LootTable table;
    private int remainingWait = -1;
    private int caughtCount;
    private boolean rodBroke;

    public FishTask(int count) {
        this(count, null);
    }

    /** @param waitSource ticks to wait per catch; tests pass a fixed value */
    public FishTask(int count, @Nullable IntSupplier waitSource) {
        this.count = count;
        this.waitSource = waitSource;
    }

    @Override
    public String type() {
        return "fish";
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().position();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (finished) {
            return TaskStatus.SUCCESS;
        }
        if (!preflightDone) {
            preflightDone = true;
            if (findRodSlot(c.companion()) < 0) {
                summary = "I have no minecraft:fishing_rod";
                failed = true;
                return TaskStatus.FAILED;
            }
            WaterSpot spot = findWater(c.level());
            if (spot == null) {
                summary = "no open water nearby";
                failed = true;
                return TaskStatus.FAILED;
            }
            water = spot.water();
            approach = new Approach(spot.stand());
            table = c.level().getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        }
        if (caughtCount >= count) {
            return finish();
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            summary = "can't reach the water at " + coords(water);
            failed = true;
            return TaskStatus.FAILED;
        }
        if (remainingWait < 0) {
            remainingWait = nextWait(c);
        }
        if (remainingWait-- > 0) {
            return TaskStatus.RUNNING;
        }
        if (!catchFish(c)) {
            return TaskStatus.FAILED;
        }
        remainingWait = -1;
        if (rodBroke) {
            return finish();
        }
        return caughtCount >= count ? finish() : TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "fishing";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private boolean catchFish(TaskContext c) {
        int slot = findRodSlot(c.companion());
        if (slot < 0) {
            summary = "I have no minecraft:fishing_rod";
            failed = true;
            return false;
        }
        ItemStack rod = c.companion().inventory().getItem(slot);
        int luck = enchantLevel(c.level(), rod, Enchantments.LUCK_OF_THE_SEA);
        LootParams params = new LootParams.Builder(c.level())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(water))
                .withParameter(LootContextParams.TOOL, rod.copy())
                .withParameter(LootContextParams.THIS_ENTITY, c.companion())
                .withLuck(luck)
                .create(LootContextParamSets.FISHING);
        for (ItemStack loot : table.getRandomItems(params)) {
            if (loot.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(loot.getItem()).toString();
            caught.merge(id, loot.getCount(), Integer::sum);
            InventoryOps.insertOrDrop(c.companion(), loot);
        }
        caughtCount++;
        c.companion().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        c.level()
                .sendParticles(
                        ParticleTypes.SPLASH,
                        water.getX() + 0.5,
                        water.getY() + 1.0,
                        water.getZ() + 0.5,
                        10,
                        0.2,
                        0.1,
                        0.2,
                        0.05);
        c.level().playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.5F, 1.0F);
        int damage = rod.getDamageValue() + 1;
        if (rod.isDamageableItem() && damage >= rod.getMaxDamage()) {
            c.companion().inventory().setItem(slot, ItemStack.EMPTY);
            c.companion().inventory().setChanged();
            rodBroke = true;
        } else {
            rod.setDamageValue(damage);
            c.companion().inventory().setChanged();
        }
        return true;
    }

    private int nextWait(TaskContext c) {
        if (waitSource != null) {
            return Math.max(1, waitSource.getAsInt());
        }
        int slot = findRodSlot(c.companion());
        int lure =
                slot < 0 ? 0 : enchantLevel(c.level(), c.companion().inventory().getItem(slot), Enchantments.LURE);
        return Math.max(20, 100 + c.level().getRandom().nextInt(500) - 100 * lure);
    }

    private TaskStatus finish() {
        finished = true;
        StringBuilder text = new StringBuilder("caught ").append(caughtCount);
        if (!caught.isEmpty()) {
            text.append(": ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : caught.entrySet()) {
                if (!first) {
                    text.append(", ");
                }
                text.append(entry.getValue()).append(' ').append(entry.getKey());
                first = false;
            }
        }
        if (rodBroke) {
            text.append("; my fishing rod broke after ").append(caughtCount).append(" catches");
            if (caughtCount == 0) {
                summary = text.toString();
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private static int findRodSlot(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(Items.FISHING_ROD)) {
                return slot;
            }
        }
        return -1;
    }

    private static int enchantLevel(
            ServerLevel level, ItemStack stack, ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        var holder =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
    }

    private WaterSpot findWater(ServerLevel level) {
        BlockPos originPos = BlockPos.containing(origin);
        BlockPos bestWater = null;
        BlockPos bestStand = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) {
                    continue;
                }
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = originPos.offset(dx, dy, dz);
                    if (!openWater(level, pos)) {
                        continue;
                    }
                    double distance = Vec3.atCenterOf(pos).distanceToSqr(origin);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    BlockPos stand = standNear(level, pos);
                    if (stand == null) {
                        continue;
                    }
                    bestDistance = distance;
                    bestWater = pos.immutable();
                    bestStand = stand;
                }
            }
        }
        return bestWater == null ? null : new WaterSpot(bestWater, bestStand);
    }

    private static boolean openWater(ServerLevel level, BlockPos pos) {
        if (!level.getFluidState(pos).isSource() || !level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        return water(level, pos)
                        && water(level, pos.east())
                        && water(level, pos.south())
                        && water(level, pos.east().south())
                || water(level, pos)
                        && water(level, pos.west())
                        && water(level, pos.south())
                        && water(level, pos.west().south())
                || water(level, pos)
                        && water(level, pos.east())
                        && water(level, pos.north())
                        && water(level, pos.east().north())
                || water(level, pos)
                        && water(level, pos.west())
                        && water(level, pos.north())
                        && water(level, pos.west().north());
    }

    private static boolean water(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).isSource() && level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static @Nullable BlockPos standNear(ServerLevel level, BlockPos water) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos solid = water.offset(dx, 0, dz);
                if (water(level, solid)
                        || level.getBlockState(solid)
                                .getCollisionShape(level, solid)
                                .isEmpty()) {
                    continue;
                }
                BlockPos stand = solid.above();
                if (!level.getBlockState(stand).isAir()
                        || !level.getBlockState(stand.above()).isAir()) {
                    continue;
                }
                double distance = Vec3.atCenterOf(stand).distanceToSqr(Vec3.atCenterOf(water));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = stand;
                }
            }
        }
        return best;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record WaterSpot(BlockPos water, BlockPos stand) {}
}
