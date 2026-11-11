package dev.friendai.perception.resource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Incremental, chunk-safe line of sight using the actual collision-independent occlusion geometry. */
public final class ResourceSight {
    private static final double EPSILON = 1.0e-7;

    private ResourceSight() {}

    public enum Outcome {
        VISIBLE,
        OCCLUDED,
        UNLOADED,
        PENDING
    }

    public interface Job {
        Outcome step(int cellBudget);

        int visitedCellsLastStep();

        BlockPos hitBlock();

        List<BlockPos> openAirCells();

        boolean unobstructedOpenCorridor();

        /**
         * Thin blocks the ray passed through before it stopped: a dead bush, a flower, a sapling. They hide nothing,
         * so they never end the ray, but their visible shape was in direct line of sight and is evidence like a hit.
         */
        List<BlockPos> glimpsed();
    }

    /** At most this many distinct kinds of thin block are reported per ray, so a meadow cannot flood the evidence. */
    static final int MAX_GLIMPSES_PER_RAY = 3;

    public static Job rayTo(ServerLevel level, Vec3 from, Vec3 to, BlockPos target) {
        return new RayJob(level, from, to, target == null ? null : target.immutable());
    }

    private static final class RayJob implements Job {
        private final ServerLevel level;
        private final Vec3 from;
        private final Vec3 to;
        private final BlockPos target;
        private double cursor;
        private Outcome outcome = Outcome.PENDING;
        private BlockPos hit;
        private int visited;
        private final List<BlockPos> openAirCells = new ArrayList<>();
        private final List<BlockPos> glimpsed = new ArrayList<>();
        private final Set<Block> glimpsedKinds = new HashSet<>();
        private boolean unobstructedOpenCorridor = true;

        private RayJob(ServerLevel level, Vec3 from, Vec3 to, BlockPos target) {
            this.level = level;
            this.from = from;
            this.to = to;
            this.target = target;
        }

        @Override
        public Outcome step(int cellBudget) {
            visited = 0;
            if (outcome != Outcome.PENDING) return outcome;
            if (cellBudget <= 0) return Outcome.PENDING;
            Vec3 delta = to.subtract(from);
            double lengthSquared = delta.lengthSqr();
            if (lengthSquared < EPSILON * EPSILON) {
                outcome = Outcome.OCCLUDED;
                return outcome;
            }
            while (cursor <= 1.0 + EPSILON && visited < cellBudget) {
                double sample = Math.min(1.0, cursor + EPSILON);
                Vec3 point = from.add(delta.scale(sample));
                BlockPos pos = BlockPos.containing(point);
                if (!level.hasChunkAt(pos)) {
                    outcome = Outcome.UNLOADED;
                    return outcome;
                }
                visited++;
                BlockState state = level.getBlockState(pos);
                if (blocksRay(state, pos, delta)) {
                    if (target == null || target.equals(pos)) {
                        hit = pos.immutable();
                        outcome = Outcome.VISIBLE;
                    } else {
                        outcome = Outcome.OCCLUDED;
                    }
                    return outcome;
                }
                if (state.isAir()) openAirCells.add(pos.immutable());
                else {
                    unobstructedOpenCorridor = false;
                    glimpse(state, pos, delta);
                }
                double next = nextBoundary(from, delta, cursor, pos);
                if (!(next > cursor + EPSILON)) next = cursor + 1.0e-5;
                cursor = next;
            }
            if (cursor > 1.0 + EPSILON) outcome = Outcome.OCCLUDED;
            return outcome;
        }

        private boolean blocksRay(BlockState state, BlockPos pos, Vec3 delta) {
            if (state.isAir() || transmitsSight(state)) return false;
            VoxelShape shape = state.getOcclusionShape();
            // Open blocks such as campfires have no occlusion shape, but their selectable geometry is still
            // directly observable when this ray is aimed at that block.
            if (shape.isEmpty() && target != null && target.equals(pos)) shape = state.getShape(level, pos);
            if (shape.isEmpty()
                    && target != null
                    && target.equals(pos)
                    && !state.getFluidState().isEmpty()) {
                float height = state.getFluidState().getHeight(level, pos);
                if (height > 0.0F) shape = Shapes.box(0.0, 0.0, 0.0, 1.0, height, 1.0);
            }
            if (shape.isEmpty() && (state.is(Blocks.TINTED_GLASS) || state.is(net.minecraft.tags.BlockTags.LEAVES))) {
                shape = state.getShape(level, pos);
            }
            if (shape.isEmpty()) return false;
            for (AABB local : shape.toAabbs()) {
                AABB world = new AABB(
                        local.minX + pos.getX(),
                        local.minY + pos.getY(),
                        local.minZ + pos.getZ(),
                        local.maxX + pos.getX(),
                        local.maxY + pos.getY(),
                        local.maxZ + pos.getZ());
                if (intersectsSegment(from, delta, world)) return true;
            }
            return false;
        }

        /**
         * Records a block the ray passes through when its visible outline lies on the ray: it does not occlude, so
         * the ray goes on, but the eye saw it. The aimed target is the ray's own business, a fluid is not a block one
         * sees the outline of, and one cell per kind is enough evidence for one ray.
         */
        private void glimpse(BlockState state, BlockPos pos, Vec3 delta) {
            if (glimpsed.size() >= MAX_GLIMPSES_PER_RAY
                    || pos.equals(target)
                    || !state.getFluidState().isEmpty()
                    || !state.getOcclusionShape().isEmpty()
                    || glimpsedKinds.contains(state.getBlock())) {
                return;
            }
            VoxelShape shape = state.getShape(level, pos);
            for (AABB local : shape.toAabbs()) {
                AABB world = local.move(pos.getX(), pos.getY(), pos.getZ());
                if (intersectsSegment(from, delta, world)) {
                    glimpsedKinds.add(state.getBlock());
                    glimpsed.add(pos.immutable());
                    return;
                }
            }
        }

        @Override
        public List<BlockPos> glimpsed() {
            return List.copyOf(glimpsed);
        }

        @Override
        public int visitedCellsLastStep() {
            return visited;
        }

        @Override
        public BlockPos hitBlock() {
            return hit;
        }

        @Override
        public List<BlockPos> openAirCells() {
            return List.copyOf(openAirCells);
        }

        @Override
        public boolean unobstructedOpenCorridor() {
            return unobstructedOpenCorridor;
        }
    }

    private static boolean transmitsSight(BlockState state) {
        if (state.is(Blocks.TINTED_GLASS)) return false;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.equals("glass")
                || path.equals("glass_pane")
                || path.endsWith("_stained_glass")
                || path.endsWith("_stained_glass_pane");
    }

    private static double nextBoundary(Vec3 from, Vec3 delta, double cursor, BlockPos cell) {
        double next = Double.POSITIVE_INFINITY;
        next = boundary(next, from.x, delta.x, cell.getX());
        next = boundary(next, from.y, delta.y, cell.getY());
        next = boundary(next, from.z, delta.z, cell.getZ());
        return next;
    }

    private static double boundary(double current, double origin, double direction, int cell) {
        if (Math.abs(direction) < EPSILON) return current;
        double edge = direction > 0 ? cell + 1.0 : cell;
        double t = (edge - origin) / direction;
        return t > EPSILON ? Math.min(current, t) : current;
    }

    private static boolean intersectsSegment(Vec3 origin, Vec3 delta, AABB box) {
        double near = 0.0;
        double far = 1.0;
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {delta.x, delta.y, delta.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < EPSILON) {
                if (o[axis] < min[axis] || o[axis] > max[axis]) return false;
                continue;
            }
            double a = (min[axis] - o[axis]) / d[axis];
            double b = (max[axis] - o[axis]) / d[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) return false;
        }
        return far >= 0.0 && near <= 1.0;
    }
}
