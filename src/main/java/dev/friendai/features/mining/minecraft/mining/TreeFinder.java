package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.features.mining.tree.TreeSummary;
import dev.friendai.features.navigation.RelativeDirection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Groups the logs around a point into trees and says what each group is: a standing tree, the floating leftovers of
 * a partly felled one, a stump, or a log structure. Read-only; it never changes the world.
 */
public final class TreeFinder {
    public enum Kind {
        STANDING_TREE("standing"),
        FLOATING("floating"),
        STUMP("stump"),
        LOG_STRUCTURE("log structure");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record TreeInfo(
            /** Short id of the most frequent log block, e.g. {@code oak_log}. */
            String type,
            /** Every log of the cluster. */
            List<BlockPos> logs,
            /** The lowest log; the one a felling starts at. */
            BlockPos base,
            /** {@code maxY - minY + 1}. */
            int height,
            Kind kind,
            /** From the origin to the centre of {@link #base}. */
            double distance,
            String direction) {

        public TreeSummary summary() {
            return new TreeSummary(
                    type,
                    kind.label(),
                    base.getX() + " " + base.getY() + " " + base.getZ(),
                    logs.size(),
                    height,
                    distance,
                    direction);
        }
    }

    /** Bounds the scan, not the tree: a jungle giant is still one cluster. */
    public static final int MAX_SCANNED_LOGS = 512;
    /** Footprints up to this many columns, without leaves, are a stump or a trunk leftover rather than a build. */
    private static final int STUMP_FOOTPRINT = 4;
    /** How far from a log a leaf block still counts as part of its tree. */
    private static final int LEAF_REACH = 2;

    private TreeFinder() {}

    /** The vertical window {@code origin.y - 16 .. origin.y + 32}, which suits tools. */
    public static List<TreeInfo> find(
            ServerLevel level, BlockPos origin, int radius, BlockMatcher typeOrNull, int limit, boolean treesOnly) {
        return find(level, origin, radius, 16, 32, typeOrNull, limit, treesOnly);
    }

    /**
     * Up to {@code limit} trees within {@code radius} blocks horizontally and {@code down}/{@code up} blocks
     * vertically of {@code origin}, tree-like kinds first and each group nearest first. Every cluster is followed down
     * to its real bottom, so {@code base} is right even when the origin is higher than the trunk's foot.
     */
    public static List<TreeInfo> find(
            ServerLevel level,
            BlockPos origin,
            int radius,
            int down,
            int up,
            BlockMatcher typeOrNull,
            int limit,
            boolean treesOnly) {
        Predicate<BlockState> isLog = typeOrNull != null ? typeOrNull::test : state -> state.is(BlockTags.LOGS);
        Set<BlockPos> scanned = scan(level, origin, radius, down, up, isLog);
        List<TreeInfo> trees = new ArrayList<>();
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (BlockPos start : scanned) {
            if (seen.contains(start)) {
                continue;
            }
            Set<BlockPos> cluster = cluster(level, start, scanned, seen, isLog, true);
            TreeInfo info = describe(level, origin, cluster);
            if (!treesOnly || info.kind() != Kind.LOG_STRUCTURE) {
                trees.add(info);
            }
        }
        orderAndLimit(trees, limit);
        return List.copyOf(trees);
    }

    /** Groups exact remembered/visible log positions without expanding into concealed connected blocks. */
    public static List<TreeInfo> findObserved(
            ServerLevel level,
            BlockPos origin,
            Map<BlockPos, String> observedLogs,
            Set<BlockPos> observedLeaves,
            int limit) {
        Set<BlockPos> scanned = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, String> entry : observedLogs.entrySet()) {
            if (scanned.size() >= MAX_SCANNED_LOGS) break;
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(entry.getValue())) continue;
            scanned.add(pos);
        }
        Set<BlockPos> leaves = new LinkedHashSet<>();
        for (BlockPos pos : observedLeaves) {
            if (level.hasChunkAt(pos) && level.getBlockState(pos).is(BlockTags.LEAVES)) leaves.add(pos);
        }
        List<TreeInfo> trees = new ArrayList<>();
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (BlockPos start : scanned) {
            if (seen.contains(start)) continue;
            Set<BlockPos> cluster = cluster(level, start, scanned, seen, state -> false, false);
            TreeInfo info = describeObserved(origin, cluster, observedLogs, leaves);
            trees.add(info);
        }
        orderAndLimit(trees, limit);
        return List.copyOf(trees);
    }

    private static TreeInfo describeObserved(
            BlockPos origin, Set<BlockPos> cluster, Map<BlockPos, String> observedLogs, Set<BlockPos> observedLeaves) {
        BlockPos base = cluster.stream()
                .min(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(pos -> pos.distSqr(origin)))
                .orElseThrow();
        int minY = cluster.stream().mapToInt(BlockPos::getY).min().orElse(base.getY());
        int maxY = cluster.stream().mapToInt(BlockPos::getY).max().orElse(base.getY());
        int minX = cluster.stream().mapToInt(BlockPos::getX).min().orElse(base.getX());
        int maxX = cluster.stream().mapToInt(BlockPos::getX).max().orElse(base.getX());
        int minZ = cluster.stream().mapToInt(BlockPos::getZ).min().orElse(base.getZ());
        int maxZ = cluster.stream().mapToInt(BlockPos::getZ).max().orElse(base.getZ());
        Map<String, Integer> types = new HashMap<>();
        cluster.forEach(pos -> types.merge(observedLogs.get(pos), 1, Integer::sum));
        String type = types.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .orElseThrow()
                .getKey();
        boolean leaves = observedLeaves.stream()
                .anyMatch(pos -> pos.getX() >= minX - LEAF_REACH
                        && pos.getX() <= maxX + LEAF_REACH
                        && pos.getY() >= minY - LEAF_REACH
                        && pos.getY() <= maxY + LEAF_REACH
                        && pos.getZ() >= minZ - LEAF_REACH
                        && pos.getZ() <= maxZ + LEAF_REACH);
        Kind kind = leaves
                ? Kind.STANDING_TREE
                : cluster.stream().map(Columns::column).distinct().count() <= STUMP_FOOTPRINT
                        ? Kind.STUMP
                        : Kind.LOG_STRUCTURE;
        return new TreeInfo(
                type,
                List.copyOf(cluster),
                base,
                maxY - minY + 1,
                kind,
                Vec3.atCenterOf(base).distanceTo(Vec3.atCenterOf(origin)),
                RelativeDirection.compass(base.getX() - origin.getX(), base.getZ() - origin.getZ()));
    }

    private static void orderAndLimit(List<TreeInfo> trees, int limit) {
        trees.sort(Comparator.<TreeInfo>comparingInt(t -> t.kind() == Kind.LOG_STRUCTURE ? 1 : 0)
                .thenComparingDouble(TreeInfo::distance)
                .thenComparingInt(t -> t.base().getX())
                .thenComparingInt(t -> t.base().getY())
                .thenComparingInt(t -> t.base().getZ()));
        if (trees.size() > limit) trees.subList(limit, trees.size()).clear();
    }

    /** Matching, breakable logs in loaded chunks, scanned in rings outwards so the cap keeps the nearest ones. */
    private static Set<BlockPos> scan(
            ServerLevel level, BlockPos origin, int radius, int down, int up, Predicate<BlockState> isLog) {
        Set<BlockPos> found = new LinkedHashSet<>();
        int minY = Math.max(level.getMinY(), origin.getY() - down);
        int maxY = Math.min(level.getMaxY(), origin.getY() + up);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                boolean edgeX = Math.abs(dx) == ring;
                for (int dz = -ring; dz <= ring; dz++) {
                    if (!edgeX && Math.abs(dz) != ring) {
                        continue;
                    }
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!level.hasChunkAt(x, z)) {
                        continue;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        pos.set(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (isLog.test(state) && state.getDestroySpeed(level, pos) >= 0) {
                            found.add(pos.immutable());
                            if (found.size() >= MAX_SCANNED_LOGS) {
                                return found;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    /** The six-connected group of {@code start} within {@code scanned}, extended down while logs continue below. */
    private static Set<BlockPos> cluster(
            ServerLevel level,
            BlockPos start,
            Set<BlockPos> scanned,
            Set<BlockPos> seen,
            Predicate<BlockState> isLog,
            boolean extendDown) {
        Set<BlockPos> cluster = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            cluster.add(pos);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (scanned.contains(next) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        if (!extendDown) return cluster;
        // The window may cut the trunk off above its foot: walk down from every column's lowest log.
        Map<Long, BlockPos> lowest = new HashMap<>();
        for (BlockPos pos : cluster) {
            lowest.merge(Columns.column(pos), pos, (a, b) -> a.getY() <= b.getY() ? a : b);
        }
        for (BlockPos foot : lowest.values()) {
            BlockPos below = foot.below();
            while (below.getY() >= level.getMinY() && level.hasChunkAt(below)) {
                BlockState state = level.getBlockState(below);
                if (!isLog.test(state) || state.getDestroySpeed(level, below) < 0 || !seen.add(below)) {
                    break;
                }
                cluster.add(below);
                below = below.below();
            }
        }
        return cluster;
    }

    private static TreeInfo describe(ServerLevel level, BlockPos origin, Set<BlockPos> cluster) {
        return describe(level, origin, cluster, Set.of(), true);
    }

    private static TreeInfo describe(
            ServerLevel level,
            BlockPos origin,
            Set<BlockPos> cluster,
            Set<BlockPos> observedLeaves,
            boolean inspectWorldLeaves) {
        BlockPos base = null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> columns = new LinkedHashSet<>();
        Map<String, Integer> types = new HashMap<>();
        for (BlockPos pos : cluster) {
            if (base == null
                    || pos.getY() < base.getY()
                    || (pos.getY() == base.getY() && pos.distSqr(origin) < base.distSqr(origin))) {
                base = pos;
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            columns.add(Columns.column(pos));
            types.merge(
                    BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(pos).getBlock())
                            .getPath(),
                    1,
                    Integer::sum);
        }
        String type = types.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .orElseThrow()
                .getKey();
        boolean supported = isSupported(level, base);
        boolean leaves = inspectWorldLeaves
                ? hasLeaves(level, minX, minY, minZ, maxX, maxY, maxZ)
                : hasObservedLeaves(observedLeaves, minX, minY, minZ, maxX, maxY, maxZ);
        Kind kind;
        if (!supported) {
            kind = Kind.FLOATING;
        } else if (leaves) {
            kind = Kind.STANDING_TREE;
        } else if (columns.size() <= STUMP_FOOTPRINT) {
            kind = Kind.STUMP;
        } else {
            kind = Kind.LOG_STRUCTURE;
        }
        double distance = Vec3.atCenterOf(base).distanceTo(Vec3.atCenterOf(origin));
        String direction = RelativeDirection.compass(base.getX() - origin.getX(), base.getZ() - origin.getZ());
        List<BlockPos> logs = Collections.unmodifiableList(new ArrayList<>(cluster));
        return new TreeInfo(type, logs, base, maxY - minY + 1, kind, distance, direction);
    }

    private static boolean hasObservedLeaves(
            Set<BlockPos> leaves, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (BlockPos pos : leaves) {
            if (pos.getX() >= minX - LEAF_REACH
                    && pos.getX() <= maxX + LEAF_REACH
                    && pos.getY() >= minY - LEAF_REACH
                    && pos.getY() <= maxY + LEAF_REACH
                    && pos.getZ() >= minZ - LEAF_REACH
                    && pos.getZ() <= maxZ + LEAF_REACH) return true;
        }
        return false;
    }

    private static boolean isSupported(ServerLevel level, BlockPos base) {
        BlockPos below = base.below();
        return level.hasChunkAt(below)
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static boolean hasLeaves(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX - LEAF_REACH; x <= maxX + LEAF_REACH; x++) {
            for (int z = minZ - LEAF_REACH; z <= maxZ + LEAF_REACH; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = Math.max(level.getMinY(), minY - LEAF_REACH);
                        y <= Math.min(level.getMaxY(), maxY + LEAF_REACH);
                        y++) {
                    if (level.getBlockState(pos.set(x, y, z)).is(BlockTags.LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Packs a column's x and z into one key. */
    private static final class Columns {
        static long column(BlockPos pos) {
            return ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
        }
    }
}
