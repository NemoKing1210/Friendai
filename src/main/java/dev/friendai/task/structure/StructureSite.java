package dev.friendai.task.structure;

import dev.friendai.features.construction.special.Offset;
import dev.friendai.features.construction.special.SpecialStructure;
import dev.friendai.task.build.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Where a special structure goes: the minimum corner of its rotated footprint and the rotation. */
public record StructureSite(BlockPos origin, Rotation rotation) {
    /** The world position of a structure-relative offset. */
    public BlockPos pos(SpecialStructure structure, Offset offset) {
        BlockPos local = Placement.rotate(
                new BlockPos(offset.x(), offset.y(), offset.z()),
                rotation,
                structure.body().width(),
                structure.body().depth());
        return origin.offset(local);
    }

    /** A block state string as it stands in the world after the site rotation. */
    public BlockState wanted(String state) {
        return Placement.parseState(state).rotate(rotation);
    }

    /** The footprint box, inclusive of its last block on every axis. */
    public AABB box(SpecialStructure structure) {
        var f = structure.footprint();
        BlockPos a = pos(structure, new Offset(0, 0, 0));
        BlockPos b = pos(structure, new Offset(f.width() - 1, f.height() - 1, f.depth() - 1));
        return new AABB(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1,
                Math.max(a.getY(), b.getY()) + 1,
                Math.max(a.getZ(), b.getZ()) + 1);
    }
}
