package dev.friendai.features.mining.excavation;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded, deterministic excavation region. For {@code box} the two corners define it; for every other shape the
 * origin is the first cell at the starting floor and the shape grows along {@link #direction}.
 */
public record ExcavationGeometry(
        ExcavationShape shape,
        int x1,
        int y1,
        int z1,
        int x2,
        int y2,
        int z2,
        ExcavationDirection direction,
        int length,
        int width,
        int height,
        int branches,
        int spacing,
        int branchLength) {
    public static final int MAX_VOLUME = 4096;
    public static final int MAX_LENGTH = 128;
    public static final int MAX_CROSS = 5;
    public static final int MAX_BRANCHES = 16;
    public static final int MAX_SPACING = 8;

    public ExcavationGeometry {
        Objects.requireNonNull(shape, "shape");
    }

    public static Optional<ExcavationGeometry> box(int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume < 1 || volume > MAX_VOLUME) {
            return Optional.empty();
        }
        return Optional.of(new ExcavationGeometry(
                ExcavationShape.BOX, minX, minY, minZ, maxX, maxY, maxZ, null, 0, 0, 0, 0, 0, 0));
    }

    public static Optional<ExcavationGeometry> tunnel(
            ExcavationDirection direction, int x, int y, int z, int length, int width, int height) {
        return corridor(ExcavationShape.TUNNEL, direction, x, y, z, length, width, height, 0, 0, 0);
    }

    public static Optional<ExcavationGeometry> stair(
            ExcavationDirection direction, int x, int y, int z, int length, int width, int height) {
        return corridor(ExcavationShape.STAIR, direction, x, y, z, length, width, height, 0, 0, 0);
    }

    public static Optional<ExcavationGeometry> shaft(
            ExcavationDirection direction, int x, int y, int z, int length, int width, int height) {
        if (direction == null || direction.horizontal()) {
            return Optional.empty();
        }
        return corridor(ExcavationShape.SHAFT, direction, x, y, z, length, width, height, 0, 0, 0);
    }

    public static Optional<ExcavationGeometry> strip(
            ExcavationDirection direction,
            int x,
            int y,
            int z,
            int length,
            int width,
            int height,
            int branches,
            int spacing,
            int branchLength) {
        if (branches < 0 || branches > MAX_BRANCHES || spacing < 2 || spacing > MAX_SPACING) {
            return Optional.empty();
        }
        if (branches > 0 && (branchLength < 1 || branchLength > MAX_LENGTH)) {
            return Optional.empty();
        }
        return corridor(
                ExcavationShape.STRIP, direction, x, y, z, length, width, height, branches, spacing, branchLength);
    }

    private static Optional<ExcavationGeometry> corridor(
            ExcavationShape shape,
            ExcavationDirection direction,
            int x,
            int y,
            int z,
            int length,
            int width,
            int height,
            int branches,
            int spacing,
            int branchLength) {
        if (direction == null || !direction.horizontal() && shape != ExcavationShape.SHAFT) {
            return Optional.empty();
        }
        if (length < 1 || length > MAX_LENGTH || width < 1 || width > MAX_CROSS || height < 1 || height > MAX_CROSS) {
            return Optional.empty();
        }
        long volume = (long) length * width * height;
        if (shape == ExcavationShape.STRIP && branches > 0) {
            volume += (long) branches * branchLength * height;
        }
        if (volume > MAX_VOLUME) {
            return Optional.empty();
        }
        return Optional.of(new ExcavationGeometry(
                shape, x, y, z, 0, 0, 0, direction, length, width, height, branches, spacing, branchLength));
    }

    public int volume() {
        return cells().size();
    }

    /** Every cell in a deterministic order: layers top-down for a box, along the run for a corridor. */
    public List<Pos> cells() {
        List<Pos> cells = new ArrayList<>();
        switch (shape) {
            case BOX -> {
                for (int y = y2; y >= y1; y--) {
                    for (int x = x1; x <= x2; x++) {
                        for (int z = z1; z <= z2; z++) {
                            cells.add(Pos.of(x, y, z));
                        }
                    }
                }
            }
            case TUNNEL, STAIR -> {
                for (int step = 0; step < length; step++) {
                    int baseX = x1 + direction.dx() * step;
                    int baseZ = z1 + direction.dz() * step;
                    int baseY = y1 - (shape == ExcavationShape.STAIR ? step : 0);
                    addCross(cells, baseX, baseY, baseZ, direction, width, height);
                }
            }
            case SHAFT -> {
                for (int step = 0; step < length; step++) {
                    int baseY = y1 + direction.dy() * step;
                    for (int x = 0; x < width; x++) {
                        for (int z = 0; z < height; z++) {
                            cells.add(Pos.of(x1 + x, baseY, z1 + z));
                        }
                    }
                }
            }
            case STRIP -> {
                int branchIndex = 0;
                for (int step = 0; step < length; step++) {
                    int baseX = x1 + direction.dx() * step;
                    int baseZ = z1 + direction.dz() * step;
                    addCross(cells, baseX, y1, baseZ, direction, width, height);
                    if (step > 0 && step % spacing == 0 && branchIndex < branches) {
                        ExcavationDirection branch = branchIndex % 2 == 0 ? direction.left() : direction.right();
                        for (int b = 1; b <= branchLength; b++) {
                            addCross(cells, baseX + branch.dx() * b, y1, baseZ + branch.dz() * b, direction, 1, height);
                        }
                        branchIndex++;
                    }
                }
            }
        }
        return List.copyOf(cells);
    }

    public boolean contains(Pos pos) {
        return cells().contains(pos);
    }

    /**
     * Adds a cross-section. Width runs along the axis perpendicular to a horizontal run (Z for east/west, X for
     * north/south); height always runs up.
     */
    private static void addCross(
            List<Pos> cells, int baseX, int baseY, int baseZ, ExcavationDirection direction, int width, int height) {
        int widthX = direction.dz() != 0 ? 1 : 0;
        int widthZ = direction.dx() != 0 ? 1 : 0;
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                cells.add(Pos.of(baseX + widthX * w, baseY + h, baseZ + widthZ * w));
            }
        }
    }
}
