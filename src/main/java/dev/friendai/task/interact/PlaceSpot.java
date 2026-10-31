package dev.friendai.task.interact;

import dev.friendai.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Finds where a block can be placed, and why a position cannot take one. Text is English, LLM-facing. */
public final class PlaceSpot {
    public record Spot(BlockPos pos, BlockPos against, Direction face) {}

    private static final Direction[] NEIGHBOURS = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    private PlaceSpot() {}

    /** Why a position cannot take a block, or empty if it can. */
    public static Optional<String> problem(ServerLevel level, BlockPos pos, Entity companion) {
        if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
            return Optional.of("y is outside the world");
        }
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced()) {
            return Optional.of(coords(pos) + " is occupied by " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        List<Entity> standing =
                level.getEntities((Entity) null, new AABB(pos), e -> !(e instanceof ItemEntity) && e.isAlive());
        if (!standing.isEmpty()) {
            return Optional.of("something is standing at " + coords(pos));
        }
        return Optional.empty();
    }

    /** A clickable neighbour face for pos: solid face below first, then the four sides, then above. */
    public static Optional<Spot> spotAt(ServerLevel level, BlockPos pos) {
        for (Direction direction : NEIGHBOURS) {
            BlockPos against = pos.relative(direction);
            Direction face = direction.getOpposite();
            if (level.getBlockState(against).isFaceSturdy(level, against, face)) {
                return Optional.of(new Spot(pos, against, face));
            }
        }
        return Optional.empty();
    }

    /**
     * Like {@link #spotAt}, but any neighbour with a collision shape counts, not only a full support face: a placed
     * slab or stairs can be clicked to place the next block, even though its side is not a full face.
     */
    public static Optional<Spot> standingSpotAt(ServerLevel level, BlockPos pos) {
        for (Direction direction : NEIGHBOURS) {
            BlockPos against = pos.relative(direction);
            BlockState state = level.getBlockState(against);
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            if (!state.getCollisionShape(level, against).isEmpty()) {
                return Optional.of(new Spot(pos, against, direction.getOpposite()));
            }
        }
        return Optional.empty();
    }

    /** An automatic spot near the companion, for blocks like a crafting table. */
    public static Optional<Spot> near(ServerLevel level, CompanionEntity companion, int radius) {
        return scan(level, companion, radius, -1, 1);
    }

    /**
     * A spot for a station the companion has to place: the cells around its feet first, then, when those offer none,
     * the rim of the hole above. A companion that mined a few blocks down has no free cell at its own level — the
     * tunnel it dug is one wide — while the ground it came from is right above its head and it can walk back up.
     */
    public static Optional<Spot> nearForStation(ServerLevel level, CompanionEntity companion, int radius) {
        Optional<Spot> atFeet = near(level, companion, radius);
        return atFeet.isPresent() ? atFeet : scan(level, companion, radius, 2, radius);
    }

    private static Optional<Spot> scan(
            ServerLevel level, CompanionEntity companion, int radius, int lowest, int highest) {
        BlockPos origin = companion.blockPosition();
        BlockPos ownerCell = companion
                .owner()
                .filter(owner -> owner.level() == level)
                .map(Entity::blockPosition)
                .orElse(null);
        BlockPos ownerAbove = ownerCell == null ? null : ownerCell.above();
        List<Spot> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < 2.0 || horizontal > radius) {
                    continue;
                }
                for (int dy = lowest; dy <= highest; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (pos.equals(origin) || pos.equals(ownerCell) || pos.equals(ownerAbove)) {
                        continue;
                    }
                    if (!level.hasChunkAt(pos)
                            || !level.getBlockState(pos).isAir()
                            || !level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                        continue;
                    }
                    if (problem(level, pos, companion).isPresent()) {
                        continue;
                    }
                    spotAt(level, pos).ifPresent(candidates::add);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Spot spot) -> horizontalDistanceSqr(origin, spot.pos()))
                .thenComparingInt(spot -> Math.abs(spot.pos().getY() - origin.getY()))
                .thenComparingInt(spot -> spot.pos().getX())
                .thenComparingInt(spot -> spot.pos().getZ()));
        return candidates.stream().findFirst();
    }

    private static double horizontalDistanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
