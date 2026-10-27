package dev.friendai.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recognises the parts of a blueprint without any world access: a floor is what the interior stands on, walls
 * separate the interior from the outside, a roof covers it, doors and gates are the entrance, glass is a window, and
 * the enclosed air is the interior volume.
 *
 * <p>Classification is geometric and conservative. The grid is the blueprint's box; positions the blueprint does not
 * list are air. Air the flood fill cannot reach from the box boundary is interior, so a blueprint with no enclosed
 * space (a wall, a fence, a platform, an open-top box or a solid cube) reports no floor, walls or roof and says so.
 *
 * <p>This is prompt and tool data only: nothing here places a block. {@link #cells(Set)} turns a selection into the
 * specs a build may place, and the tool layer rejects {@link PartKind#INTERIOR} because it is empty space.
 */
public final class StructureParts {
    private static final String AIR = "minecraft:air";
    private static final int[][] SIDES = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    private final Map<BlockSpec, PartKind> roles;
    private final EnumMap<PartKind, Integer> counts;
    private final int interiorVolume;

    private StructureParts(
            Map<BlockSpec, PartKind> roles, EnumMap<PartKind, Integer> counts, int interiorVolume) {
        this.roles = Map.copyOf(roles);
        this.counts = new EnumMap<>(counts);
        this.interiorVolume = interiorVolume;
    }

    public static StructureParts analyse(Blueprint blueprint) {
        int width = Math.max(1, blueprint.width());
        int height = Math.max(1, blueprint.height());
        int depth = Math.max(1, blueprint.depth());
        String[][][] grid = new String[width][height][depth];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    grid[x][y][z] = AIR;
                }
            }
        }
        for (BlockSpec spec : blueprint.blocks()) {
            if (inside(spec, width, height, depth)) {
                grid[spec.x()][spec.y()][spec.z()] = spec.state();
            }
        }

        boolean[][][] outside = floodOutside(grid, width, height, depth);
        boolean[][][] interior = new boolean[width][height][depth];
        int interiorVolume = 0;
        int interiorTop = -1;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    if (isAir(grid[x][y][z]) && !outside[x][y][z]) {
                        interior[x][y][z] = true;
                        interiorVolume++;
                        interiorTop = Math.max(interiorTop, y);
                    }
                }
            }
        }

        boolean enclosed = interiorVolume > 0;
        int lowestSolid = lowestSolid(blueprint);
        Map<BlockSpec, PartKind> roles = new LinkedHashMap<>();
        EnumMap<PartKind, Integer> counts = new EnumMap<>(PartKind.class);
        for (BlockSpec spec : blueprint.blocks()) {
            if (isAir(spec.state())) {
                PartKind role = inside(spec, width, height, depth)
                                && interior[spec.x()][spec.y()][spec.z()]
                        ? PartKind.INTERIOR
                        : PartKind.OTHER;
                roles.put(spec, role);
                continue;
            }
            PartKind role = classify(grid, spec, interior, interiorTop, enclosed, lowestSolid, width, height, depth);
            roles.put(spec, role);
            counts.merge(role, 1, Integer::sum);
        }
        counts.put(PartKind.INTERIOR, interiorVolume);
        return new StructureParts(roles, counts, interiorVolume);
    }

    /** The role of a blueprint cell; null for a spec outside the blueprint's box. */
    public PartKind roleOf(BlockSpec spec) {
        return roles.get(spec);
    }

    public int count(PartKind kind) {
        return counts.getOrDefault(kind, 0);
    }

    /**
     * The non-air cells of the selected parts, in blueprint order. An empty selection means every non-air cell;
     * {@link PartKind#INTERIOR} selects nothing because it is air.
     */
    public List<BlockSpec> cells(Set<PartKind> selected) {
        boolean all = selected == null || selected.isEmpty();
        List<BlockSpec> result = new ArrayList<>();
        for (Map.Entry<BlockSpec, PartKind> entry : roles.entrySet()) {
            if (isAir(entry.getKey().state())) {
                continue;
            }
            if (all || selected.contains(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        result.sort(Comparator.comparingInt(BlockSpec::x)
                .thenComparingInt(BlockSpec::y)
                .thenComparingInt(BlockSpec::z));
        return List.copyOf(result);
    }

    /** One English line for a preview or a result summary. */
    public String describe() {
        return describe(Set.of());
    }

    /** The selected parts' counts; an empty selection describes every part. */
    public String describe(Set<PartKind> selected) {
        boolean all = selected == null || selected.isEmpty();
        if (interiorVolume == 0) {
            int blocks = 0;
            for (Map.Entry<PartKind, Integer> entry : counts.entrySet()) {
                if (entry.getKey() != PartKind.INTERIOR) {
                    blocks += entry.getValue();
                }
            }
            return "no enclosed interior; " + blocks + " block" + (blocks == 1 ? "" : "s");
        }
        List<String> parts = new ArrayList<>();
        for (PartKind kind : new PartKind[] {
            PartKind.FLOOR, PartKind.WALLS, PartKind.ROOF, PartKind.ENTRANCE, PartKind.WINDOW, PartKind.OTHER
        }) {
            if (!all && !selected.contains(kind)) {
                continue;
            }
            int count = count(kind);
            if (count > 0) {
                parts.add(label(kind) + " " + count);
            }
        }
        if (all || selected.contains(PartKind.INTERIOR)) {
            parts.add("interior " + interiorVolume);
        }
        return parts.isEmpty() ? "no parts" : String.join(", ", parts);
    }

    private static String label(PartKind kind) {
        return switch (kind) {
            case FLOOR -> "floor";
            case WALLS -> "walls";
            case ROOF -> "roof";
            case ENTRANCE -> "entrance";
            case WINDOW -> "windows";
            case INTERIOR -> "interior";
            case OTHER -> "other";
        };
    }

    private static int lowestSolid(Blueprint blueprint) {
        int lowest = Integer.MAX_VALUE;
        for (BlockSpec spec : blueprint.blocks()) {
            if (!isAir(spec.state())) {
                lowest = Math.min(lowest, spec.y());
            }
        }
        return lowest == Integer.MAX_VALUE ? -1 : lowest;
    }

    private static PartKind classify(
            String[][][] grid,
            BlockSpec spec,
            boolean[][][] interior,
            int interiorTop,
            boolean enclosed,
            int lowestSolid,
            int width,
            int height,
            int depth) {
        String base = base(spec.state());
        if (base.endsWith("_door") || base.endsWith("_fence_gate")) {
            return PartKind.ENTRANCE;
        }
        if (base.contains("glass")) {
            return PartKind.WINDOW;
        }
        if (!enclosed) {
            return PartKind.OTHER;
        }
        int x = spec.x();
        int y = spec.y();
        int z = spec.z();
        // The whole bottom layer is the floor, including the cells the walls stand on.
        if (y == lowestSolid) {
            return PartKind.FLOOR;
        }
        if (y + 1 < height && interior[x][y + 1][z]) {
            return PartKind.FLOOR;
        }
        if ((y > 0 && interior[x][y - 1][z]) || y > interiorTop) {
            return PartKind.ROOF;
        }
        if (neighbourIsInterior(interior, x, y, z, width, height, depth)) {
            return PartKind.WALLS;
        }
        return PartKind.OTHER;
    }

    private static boolean neighbourIsInterior(
            boolean[][][] interior, int x, int y, int z, int width, int height, int depth) {
        for (int[] side : SIDES) {
            int nx = x + side[0];
            int ny = y + side[1];
            int nz = z + side[2];
            if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= depth) {
                continue;
            }
            if (interior[nx][ny][nz]) {
                return true;
            }
        }
        return false;
    }

    private static boolean[][][] floodOutside(String[][][] grid, int width, int height, int depth) {
        boolean[][][] outside = new boolean[width][height][depth];
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    boolean boundary = x == 0 || x == width - 1 || y == 0 || y == height - 1 || z == 0
                            || z == depth - 1;
                    if (boundary && isAir(grid[x][y][z]) && !outside[x][y][z]) {
                        outside[x][y][z] = true;
                        queue.add(new int[] {x, y, z});
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] side : SIDES) {
                int nx = cell[0] + side[0];
                int ny = cell[1] + side[1];
                int nz = cell[2] + side[2];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= depth) {
                    continue;
                }
                if (!outside[nx][ny][nz] && isAir(grid[nx][ny][nz])) {
                    outside[nx][ny][nz] = true;
                    queue.add(new int[] {nx, ny, nz});
                }
            }
        }
        return outside;
    }

    private static boolean inside(BlockSpec spec, int width, int height, int depth) {
        return spec.x() >= 0 && spec.x() < width && spec.y() >= 0 && spec.y() < height && spec.z() >= 0
                && spec.z() < depth;
    }

    private static boolean isAir(String state) {
        String base = base(state);
        return base.equals(AIR) || base.endsWith(":cave_air") || base.endsWith(":void_air");
    }

    private static String base(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }
}
