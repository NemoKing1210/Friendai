package dev.friendai.task.build;

import dev.friendai.build.BlockSpec;
import dev.friendai.build.Blueprint;
import dev.friendai.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Turns a blueprint into world positions and states: either at an explicit origin, or 3 blocks in front of the owner
 * with the structure's front (its south side) facing back at the owner.
 */
public final class Placement {
    public record PlacedSpec(BlockPos pos, BlockState wanted) {}

    public record PlacedBlueprint(List<PlacedSpec> specs, BlockPos origin, Rotation rotation) {
        public PlacedBlueprint {
            specs = List.copyOf(specs);
        }
    }

    private Placement() {}

    /**
     * @param origin      the minimum corner of the rotated footprint, or null to place in front of the companion
     * @param facing      the world direction the structure's front should face, or null to face the owner
     * @param companion   the builder; needed when the origin is not given
     */
    public static PlacedBlueprint of(
            Blueprint blueprint, BlockPos origin, Direction facing, CompanionEntity companion) {
        Rotation rotation;
        BlockPos corner;
        if (origin != null) {
            rotation = rotationFor(facing == null ? Direction.SOUTH : facing);
            corner = origin;
        } else {
            if (companion == null) {
                throw new IllegalArgumentException("no place to build");
            }
            Entity anchor = anchor(companion);
            Direction look = horizontal(anchor.getDirection());
            Direction front = facing == null ? look.getOpposite() : horizontal(facing);
            rotation = rotationFor(front);
            int width = rotatedWidth(blueprint, rotation);
            int depth = rotatedDepth(blueprint, rotation);
            BlockPos anchorPos = anchor.blockPosition();
            int x;
            int z;
            switch (look) {
                case NORTH -> {
                    x = anchorPos.getX() - width / 2;
                    z = anchorPos.getZ() - 3 - depth + 1;
                }
                case EAST -> {
                    x = anchorPos.getX() + 3;
                    z = anchorPos.getZ() - depth / 2;
                }
                case WEST -> {
                    x = anchorPos.getX() - 3 - width + 1;
                    z = anchorPos.getZ() - depth / 2;
                }
                default -> {
                    x = anchorPos.getX() - width / 2;
                    z = anchorPos.getZ() + 3;
                }
            }
            corner = new BlockPos(
                    x, level(companion).getHeight(Heightmap.Types.WORLD_SURFACE, x + width / 2, z + depth / 2), z);
        }

        List<PlacedSpec> specs = new ArrayList<>(blueprint.blocks().size());
        for (BlockSpec spec : blueprint.blocks()) {
            BlockPos local =
                    rotate(new BlockPos(spec.x(), spec.y(), spec.z()), rotation, blueprint.width(), blueprint.depth());
            specs.add(new PlacedSpec(
                    corner.offset(local), parseState(spec.state()).rotate(rotation)));
        }
        return new PlacedBlueprint(specs, corner, rotation);
    }

    /** The blueprint placed at an explicit origin with an explicit rotation. */
    public static PlacedBlueprint at(Blueprint blueprint, BlockPos origin, Rotation rotation) {
        List<PlacedSpec> specs = new ArrayList<>(blueprint.blocks().size());
        for (BlockSpec spec : blueprint.blocks()) {
            BlockPos local =
                    rotate(new BlockPos(spec.x(), spec.y(), spec.z()), rotation, blueprint.width(), blueprint.depth());
            specs.add(new PlacedSpec(
                    origin.offset(local), parseState(spec.state()).rotate(rotation)));
        }
        return new PlacedBlueprint(specs, origin, rotation);
    }

    /** The owner when online, otherwise the companion itself. */
    private static Entity anchor(CompanionEntity companion) {
        return companion
                .owner()
                .filter(owner -> owner.level() == companion.level())
                .map(Entity.class::cast)
                .orElse(companion);
    }

    private static ServerLevel level(CompanionEntity companion) {
        return (ServerLevel) companion.level();
    }

    public static Rotation rotationFor(Direction facing) {
        return switch (facing) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** The direction the structure's front faces after this rotation. */
    public static Direction facingFor(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Direction.WEST;
            case CLOCKWISE_180 -> Direction.NORTH;
            case COUNTERCLOCKWISE_90 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }

    /** The footprint size after rotation: width along x, depth along z. */
    public static int rotatedWidth(Blueprint blueprint, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? blueprint.depth()
                : blueprint.width();
    }

    public static int rotatedDepth(Blueprint blueprint, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? blueprint.width()
                : blueprint.depth();
    }

    /** Rotates a position within the blueprint's box; width/depth are the blueprint's unrotated sizes. */
    public static BlockPos rotate(BlockPos pos, Rotation rotation, int width, int depth) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(depth - 1 - pos.getZ(), pos.getY(), pos.getX());
            case CLOCKWISE_180 -> new BlockPos(width - 1 - pos.getX(), pos.getY(), depth - 1 - pos.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(pos.getZ(), pos.getY(), width - 1 - pos.getX());
            default -> pos;
        };
    }

    private static Direction horizontal(Direction direction) {
        return direction.getAxis().isHorizontal() ? direction : Direction.SOUTH;
    }

    public static BlockState parseState(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false)
                    .blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown block " + state);
        }
    }
}
