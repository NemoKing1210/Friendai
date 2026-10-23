package dev.friendai.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Pieces of a blueprint that the game places on its own: a door's upper half comes with the lower one, and a bed
 * always arrives as a pair.
 *
 * <p>The blueprint names one of the two cells, and the game orients the other one by the way the companion happened
 * to face when it placed the block, not by what the blueprint says. Reconciling moves the partner to the cell the
 * blueprint asks for and gives it the blueprint's state, removing the one the placement left behind.
 *
 * <p>The cells are written without neighbour updates on purpose: the pair is briefly inconsistent between the two
 * writes, and a neighbour update in that moment would make the game break the block it had just placed.
 */
public final class Parts {
    private Parts() {}

    /** Makes the cell at pos match wanted and brings its partner in line with it. */
    public static void reconcile(ServerLevel level, BlockPos pos, BlockState wanted) {
        if (level.getBlockState(pos).getBlock() != wanted.getBlock()) {
            return;
        }
        if (wanted.getBlock() instanceof DoorBlock && wanted.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            reconcileDoor(level, pos, wanted);
        } else if (wanted.getBlock() instanceof AbstractBedBlock) {
            reconcileBed(level, pos, wanted);
            return;
        }
        level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
    }

    /** The upper half is always directly above the lower one; only its state can be wrong. */
    private static void reconcileDoor(ServerLevel level, BlockPos pos, BlockState wanted) {
        BlockState upperWanted = wanted.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        BlockPos upperPos = pos.above();
        BlockState upper = level.getBlockState(upperPos);
        if (upper.getBlock() == wanted.getBlock() && !upper.equals(upperWanted)) {
            level.setBlock(upperPos, upperWanted, Block.UPDATE_CLIENTS);
        }
        level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
    }

    private static void reconcileBed(ServerLevel level, BlockPos pos, BlockState wanted) {
        BedPart part = wanted.getValue(AbstractBedBlock.PART);
        Direction facing = wanted.getValue(HorizontalDirectionalBlock.FACING);
        BlockPos partnerPos = part == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
        BedPart partnerPart = part == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT;
        BlockState partner = wanted.setValue(AbstractBedBlock.PART, partnerPart);

        BlockPos stray = stray(level, pos, wanted.getBlock(), partnerPart, partnerPos);
        BlockState atPartner = level.getBlockState(partnerPos);
        if (!atPartner.equals(partner)) {
            if (!atPartner.isAir() && !atPartner.canBeReplaced() && atPartner.getBlock() != wanted.getBlock()) {
                // Someone else's block sits where the other half belongs; leave the bed alone rather than half-place it.
                return;
            }
            level.setBlock(partnerPos, partner, Block.UPDATE_CLIENTS);
        }
        level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
        if (stray != null) {
            level.setBlock(stray, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** The other half where the placement left it, when that is not where the blueprint wants it. */
    private static BlockPos stray(
            ServerLevel level, BlockPos pos, Block block, BedPart part, BlockPos partnerPos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (neighbor.equals(partnerPos)) {
                continue;
            }
            BlockState state = level.getBlockState(neighbor);
            if (state.getBlock() == block && state.getValue(AbstractBedBlock.PART) == part) {
                return neighbor;
            }
        }
        return null;
    }
}
