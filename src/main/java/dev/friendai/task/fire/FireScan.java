package dev.friendai.task.fire;

import dev.friendai.crafting.Station;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.fire.BurningCell;
import dev.friendai.fire.FireFacts;
import dev.friendai.fire.FireTarget;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.autonomy.PlaceGuard;
import dev.friendai.task.nav.LevelCellView;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * Reads the world for one fire decision: which cells burn, what each of them allows, and what the bag holds.
 * Server thread only; the result is plain data for {@link dev.friendai.fire.FirePlanner}.
 */
public final class FireScan {
    /** How close the companion has to be to act on a cell, matching {@code Approach.REACH}. */
    public static final int REACH = 4;
    /** How far away the owner is still worth helping. */
    public static final int OWNER_REACH = 8;
    /** The default radius a scan covers when the tool names none. */
    public static final int DEFAULT_RADIUS = 8;

    /** Blocks water must not flow into: it would wash crops away or break a circuit. */
    /** How far a placed source can reach along the ground. */
    private static final int FLOW_RADIUS = 7;

    private static final Set<Block> FLOW_SENSITIVE = Set.of(
            Blocks.REDSTONE_WIRE,
            Blocks.REDSTONE_TORCH,
            Blocks.REDSTONE_WALL_TORCH,
            Blocks.REPEATER,
            Blocks.COMPARATOR,
            Blocks.LEVER,
            Blocks.TRIPWIRE,
            Blocks.TRIPWIRE_HOOK,
            Blocks.DAYLIGHT_DETECTOR,
            Blocks.FARMLAND);

    /** One scan: the judged cells and the facts that are not per cell. */
    public record Scan(List<BurningCell> cells, FireFacts facts) {}

    private FireScan() {}

    public static Scan collect(TaskContext c, FireTarget target) {
        return collect(c, target, DEFAULT_RADIUS);
    }

    public static Scan collect(TaskContext c, FireTarget target, int radius) {
        CompanionEntity companion = c.companion();
        ServerLevel level = c.level();
        List<BurningCell> cells = new ArrayList<>();
        switch (target) {
            case FireTarget.Block block -> judge(c, block.pos(), radius, cells);
            case FireTarget.Area area -> {
                for (Pos pos : area.cells()) {
                    judge(c, pos, radius, cells);
                }
            }
            case FireTarget.Self self -> judge(c, toPos(entityPos(companion)), radius, cells);
            case FireTarget.Owner owner -> judge(c, toPos(entityPos(companion)), radius, cells);
            case FireTarget.BurningEntity entity -> judge(c, toPos(entityPos(companion)), radius, cells);
        }
        BlockPos focus = switch (target) {
            case FireTarget.Block block -> blockPos(block.pos());
            case FireTarget.Area area -> area.cells().isEmpty() ? entityPos(companion) : blockPos(area.cells().getFirst());
            case FireTarget.Self self -> entityPos(companion);
            case FireTarget.Owner owner -> ownerPos(c).orElse(entityPos(companion));
            case FireTarget.BurningEntity entity -> blockPos(entity.pos());
        };
        FireFacts facts = facts(c, target, focus, radius);
        return new Scan(cells, facts);
    }

    /** Every burning cell within {@code radius} of the companion, judged like a named target. */
    public static List<BurningCell> burningCellsNear(TaskContext c, int radius) {
        ServerLevel level = c.level();
        BlockPos center = entityPos(c.companion());
        List<BurningCell> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isLoaded(pos) || kind(level.getBlockState(pos)) == null) {
                        continue;
                    }
                    judge(c, toPos(pos), radius, cells);
                }
            }
        }
        return cells;
    }

    /** The nearest burning mob or animal that is neither the companion nor its owner. */
    public static java.util.Optional<Pos> burningEntityNear(TaskContext c, int radius) {
        return burningEntityNear(c.companion(), radius);
    }

    /** The same scan without a task context, for a tool that only runs on the server thread. */
    public static java.util.Optional<Pos> burningEntityNear(CompanionEntity companion, int radius) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return java.util.Optional.empty();
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(companion.blockPosition()).inflate(radius))) {
            if (entity == companion || !entity.isOnFire()
                    || (companion.ownerUuid() != null && entity.getUUID().equals(companion.ownerUuid()))) {
                continue;
            }
            double distance = entity.distanceToSqr(companion);
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best == null ? java.util.Optional.empty() : java.util.Optional.of(toPos(best.blockPosition()));
    }

    /** Whether a cell may safely hold water: no nether, no portal, no crops or redstone in the flood path. */
    public static boolean waterSafe(TaskContext c, BlockPos cell) {
        ServerLevel level = c.level();
        if (level.dimension() == Level.NETHER) {
            return false;
        }
        if (!c.companion().hands().canInteract(cell) || PlaceGuard.protects(c, cell)) {
            return false;
        }
        if (portalNear(level, cell)) {
            return false;
        }
        if (flowSensitiveNear(level, cell)) {
            return false;
        }
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(cell), e -> e != c.companion())) {
            return false;
        }
        return true;
    }

    /**
     * Whether water poured here would reach crops or a circuit: a source spreads up to seven blocks along the level
     * it lands on, so the footprint, not the six neighbours, is what matters.
     */
    private static boolean flowSensitiveNear(ServerLevel level, BlockPos cell) {
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -FLOW_RADIUS; dx <= FLOW_RADIUS; dx++) {
                for (int dz = -FLOW_RADIUS; dz <= FLOW_RADIUS; dz++) {
                    BlockPos p = cell.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(p);
                    if (state.is(BlockTags.CROPS) || FLOW_SENSITIVE.contains(state.getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether breaking this cell would disturb a nether portal: a live portal or an obsidian frame around it. */
    public static boolean inPortalFrame(ServerLevel level, BlockPos cell) {
        int frame = 0;
        for (Direction direction : Direction.values()) {
            BlockState state = level.getBlockState(cell.relative(direction));
            if (state.is(Blocks.NETHER_PORTAL)) {
                return true;
            }
            if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) {
                frame++;
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState diagonal = level.getBlockState(cell.relative(direction).relative(direction.getClockWise()));
            if (diagonal.is(Blocks.OBSIDIAN) || diagonal.is(Blocks.CRYING_OBSIDIAN)) {
                frame++;
            }
        }
        return frame >= 2;
    }

    private static void judge(TaskContext c, Pos pos, int radius, List<BurningCell> into) {
        ServerLevel level = c.level();
        BlockPos cell = blockPos(pos);
        BurningCell.Kind kind = kind(level.getBlockState(cell));
        if (kind == null) {
            return;
        }
        boolean protectedCell = !c.companion().hands().canInteract(cell) || PlaceGuard.protects(c, cell);
        boolean reachable = withinScan(c.companion(), cell, radius);
        BurningCell.Rails rails = new BurningCell.Rails(
                reserved(c, cell, kind),
                inPortalFrame(level, cell),
                waterSafe(c, cell),
                breakable(c, cell),
                reachable,
                protectedCell);
        into.add(new BurningCell(pos, kind, rails));
    }

    private static BurningCell.Kind kind(BlockState state) {
        if (state.is(Blocks.FIRE)) {
            return BurningCell.Kind.FIRE;
        }
        if (state.is(Blocks.SOUL_FIRE)) {
            return BurningCell.Kind.SOUL_FIRE;
        }
        if (state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT)) {
            return BurningCell.Kind.LIT_CAMPFIRE;
        }
        return null;
    }

    private static boolean reserved(TaskContext c, BlockPos cell, BurningCell.Kind kind) {
        if (kind != BurningCell.Kind.LIT_CAMPFIRE) {
            return false;
        }
        CompanionEntity companion = c.companion();
        long now = c.level().getGameTime();
        OptionalLong held = companion.stationLease().held(Station.CAMPFIRE, now);
        if (held.isPresent() && held.getAsLong() == cell.asLong()) {
            return true;
        }
        return CampfireBlock.isSmokeyPos(c.level(), cell);
    }

    private static boolean breakable(TaskContext c, BlockPos cell) {
        ServerLevel level = c.level();
        BlockState state = level.getBlockState(cell);
        if (!state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)) {
            return false;
        }
        if (state.getDestroySpeed(level, cell) < 0) {
            return false;
        }
        if (c.companion().creativeMode() && TaskSettings.breakList.matches(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), LevelCellView.tagsOf(state))) {
            return false;
        }
        return c.companion().hands().canInteract(cell) && !PlaceGuard.protects(c, cell);
    }

    private static FireFacts facts(TaskContext c, FireTarget target, BlockPos focus, int radius) {
        CompanionEntity companion = c.companion();
        ServerLevel level = c.level();
        boolean nether = level.dimension() == Level.NETHER;
        boolean raining = level.isRainingAt(focus);
        Pos self = toPos(entityPos(companion));
        Pos waterSource = nether ? null : waterSourceNear(c, entityPos(companion));
        Pos waterCell = switch (target) {
            case FireTarget.Self ignored -> waterSafe(c, entityPos(companion)) ? self : null;
            case FireTarget.Owner ignored -> nether ? null : waterCellBeside(c, focus);
            case FireTarget.BurningEntity entity -> nether ? null : waterCellBeside(c, blockPos(entity.pos()));
            case FireTarget.Block block -> nether ? null : waterSafe(c, blockPos(block.pos()))
                    ? block.pos() : null;
            case FireTarget.Area area -> nether ? null : firstSafeWaterCell(c, area);
        };
        boolean inReach = companionDistanceSqr(companion, focus) <= (long) OWNER_REACH * OWNER_REACH;
        return new FireFacts(nether, raining, self, count(c, "minecraft:water_bucket"),
                count(c, "minecraft:powder_snow_bucket"), waterSource, waterCell, inReach);
    }

    private static Pos firstSafeWaterCell(TaskContext c, FireTarget.Area area) {
        for (Pos pos : area.cells()) {
            if (waterSafe(c, blockPos(pos))) {
                return pos;
            }
        }
        return null;
    }

    private static Pos waterCellBeside(TaskContext c, BlockPos target) {
        ServerLevel level = c.level();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos cell = target.relative(direction);
            if (level.getBlockState(cell).isAir() && waterSafe(c, cell)) {
                return toPos(cell);
            }
        }
        BlockPos below = target.below();
        if (level.getBlockState(below).isAir() && waterSafe(c, below)) {
            return toPos(below);
        }
        return null;
    }

    private static Pos waterSourceNear(TaskContext c, BlockPos center) {
        ServerLevel level = c.level();
        Pos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.getFluidState(pos).getType().isSame(Fluids.WATER)) {
                        continue;
                    }
                    if (!level.getFluidState(pos).isSource()) {
                        continue;
                    }
                    if (!level.getBlockState(pos).getFluidState().isSource()) {
                        continue;
                    }
                    long distance = (long) dx * dx + (long) dz * dz;
                    if (distance <= (long) REACH * REACH && distance < bestDistance) {
                        best = toPos(pos);
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean portalNear(ServerLevel level, BlockPos cell) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(cell.relative(direction)).is(Blocks.NETHER_PORTAL)) {
                return true;
            }
        }
        return level.getBlockState(cell).is(Blocks.NETHER_PORTAL);
    }

    private static int count(TaskContext c, String itemId) {
        return dev.friendai.task.inventory.InventoryOps.count(c.companion().inventory(),
                dev.friendai.task.inventory.ItemMatcher.parse(itemId).orElseThrow());
    }

    private static long companionDistanceSqr(CompanionEntity companion, BlockPos pos) {
        return (long) companion.blockPosition().distSqr(pos);
    }

    /** The scan covers a cube, so what counts as reachable has to be measured the same way. */
    private static boolean withinScan(CompanionEntity companion, BlockPos pos, int radius) {
        BlockPos center = companion.blockPosition();
        return Math.abs(center.getX() - pos.getX()) <= radius
                && Math.abs(center.getY() - pos.getY()) <= radius
                && Math.abs(center.getZ() - pos.getZ()) <= radius;
    }

    private static BlockPos entityPos(CompanionEntity companion) {
        return companion.blockPosition();
    }

    private static java.util.Optional<BlockPos> ownerPos(TaskContext c) {
        return c.owner().map(owner -> owner.blockPosition());
    }

    private static BlockPos blockPos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static Pos toPos(BlockPos pos) {
        return Pos.of(pos.getX(), pos.getY(), pos.getZ());
    }
}
