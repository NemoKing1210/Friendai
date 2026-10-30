package dev.friendai.features.survival.minecraft.selfcare;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.movement.Facing;
import dev.friendai.features.survival.selfcare.SelfCareItem;
import dev.friendai.features.survival.selfcare.SelfCarePlanner;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * The self-care layer's hands: it reads the ground below a falling companion and spends a bucket or a splash potion
 * through {@link dev.friendai.task.interact.CompanionHands}, so protection applies and nothing can be lost — the fake
 * player's leftovers always come back. Every aim here is written from the shipped 26.3 bucket: {@code useItem}
 * (right-click in the air) is the call that pours and that picks a source up again.
 */
public final class SelfCareHands {
    private final CompanionEntity companion;
    private final ServerLevel level;

    public SelfCareHands(CompanionEntity companion) {
        this.companion = companion;
        this.level = (ServerLevel) companion.level();
    }

    /**
     * The solid cell under the feet whose top face the companion can pour onto, or empty when the first solid cell
     * below is out of the bucket's reach, is protected, holds a fluid itself, or has nothing replaceable above it.
     */
    public Optional<BlockPos> landingSupport() {
        BlockPos feet = companion.blockPosition();
        Vec3 position = companion.position();
        int reach = (int) Math.ceil(SelfCarePlanner.WATER_REACH_BLOCKS);
        for (int depth = 1; depth <= reach; depth++) {
            BlockPos cell = feet.below(depth);
            BlockState state = level.getBlockState(cell);
            if (state.getCollisionShape(level, cell).isEmpty()) {
                continue;
            }
            double distance = position.y - (cell.getY() + 1);
            if (distance <= 0.0 || distance > SelfCarePlanner.WATER_REACH_BLOCKS) {
                return Optional.empty();
            }
            if (!state.getFluidState().isEmpty()) {
                return Optional.empty();
            }
            BlockPos landing = cell.above();
            BlockState above = level.getBlockState(landing);
            // Water on lava is not a landing: the source is gone and the fall is not caught.
            if (!above.getFluidState().isEmpty() || !above.canBeReplaced()) {
                return Optional.empty();
            }
            if (!companion.hands().canInteract(cell) || !companion.hands().canInteract(landing)) {
                return Optional.empty();
            }
            return Optional.of(cell);
        }
        return Optional.empty();
    }

    /** Pours one water bucket onto the top face of {@code support}; the cell the source landed in, or null. */
    public BlockPos pourWater(BlockPos support) {
        ItemStack bucket = SelfCareItems.take(companion.inventory(), SelfCareItem.WATER_BUCKET);
        if (bucket.isEmpty()) {
            return null;
        }
        Vec3 aim = Vec3.atCenterOf(support).relative(Direction.UP, 0.5);
        companion.hands().useItem(bucket, aim);
        BlockPos cell = support.above();
        return holdsSource(cell) ? cell : null;
    }

    /** Takes the source at {@code cell} back with an empty bucket; true when no source is left there. */
    public boolean recover(BlockPos cell) {
        if (!holdsSource(cell)) {
            return true;
        }
        ItemStack empty = SelfCareItems.take(companion.inventory(), SelfCareItem.EMPTY_BUCKET);
        if (empty.isEmpty()) {
            return false;
        }
        companion.hands().useItem(empty, Vec3.atCenterOf(cell));
        return !holdsSource(cell);
    }

    /** Whether exactly this cell holds a water source that is ours to take back. */
    public boolean holdsSource(BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (state.getBlock() instanceof SimpleWaterloggedBlock) {
            return false;
        }
        return level.getFluidState(cell).getType().isSame(Fluids.WATER)
                && level.getFluidState(cell).isSource();
    }

    /** Throws one splash potion at {@code target}; true when it left the inventory. */
    public boolean throwSplashAt(ServerPlayer target) {
        ItemStack potion = SelfCareItems.take(companion.inventory(), SelfCareItem.SPLASH_POTION_HEALING);
        if (potion.isEmpty()) {
            return false;
        }
        Vec3 eye = companion.getEyePosition();
        Vec3 aim = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        companion.facing().lookAt(aim, 2);
        ThrownSplashPotion thrown = new ThrownSplashPotion(level, companion, potion);
        thrown.shootFromRotation(
                companion, Facing.pitchToward(dx, dy, dz), Facing.yawToward(dx, dz), -10.0F, 0.5F, 1.0F);
        level.addFreshEntity(thrown);
        return true;
    }
}
