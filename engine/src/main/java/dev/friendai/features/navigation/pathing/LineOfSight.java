package dev.friendai.features.navigation.pathing;

/**
 * Whether a straight line from an eye to a block's centre meets no blocking cell, as vanilla targeting requires.
 *
 * <p>Voxel traversal (Amanatides–Woo): walk cell by cell from the eye and stop when the ray enters the target
 * cell. Full and tall blocks and closed doors block; glass counts as solid, so a target behind glass is hidden.
 */
public final class LineOfSight {

    /** How far past the last block boundary a ray may run before it is treated as having reached the target. */
    private static final double EPSILON = 1e-7;

    private LineOfSight() {}

    /** How far inside a face the aim points lie, so a ray to one of them enters the target cell through that face. */
    private static final double FACE_INSET = 0.05;

    /**
     * Whether a player could aim at the target from the eye: at its centre or at any of its six faces. A block beside
     * the feet with stone above it is hidden from the eye by that stone when aimed at the centre, yet its side face is
     * in plain view.
     */
    public static boolean clear(CellView view, double ex, double ey, double ez, Pos target) {
        double cx = target.x() + 0.5;
        double cy = target.y() + 0.5;
        double cz = target.z() + 0.5;
        double near = 0.5 - FACE_INSET;
        return clearTo(view, ex, ey, ez, target, cx, cy, cz)
                || clearTo(view, ex, ey, ez, target, cx + near, cy, cz)
                || clearTo(view, ex, ey, ez, target, cx - near, cy, cz)
                || clearTo(view, ex, ey, ez, target, cx, cy + near, cz)
                || clearTo(view, ex, ey, ez, target, cx, cy - near, cz)
                || clearTo(view, ex, ey, ez, target, cx, cy, cz + near)
                || clearTo(view, ex, ey, ez, target, cx, cy, cz - near);
    }

    private static boolean clearTo(
            CellView view, double ex, double ey, double ez, Pos target, double px, double py, double pz) {
        int tx = target.x();
        int ty = target.y();
        int tz = target.z();
        double dx = px - ex;
        double dy = py - ey;
        double dz = pz - ez;

        int cx = (int) Math.floor(ex);
        int cy = (int) Math.floor(ey);
        int cz = (int) Math.floor(ez);
        if (cx == tx && cy == ty && cz == tz) {
            return true;
        }

        int stepX = Double.compare(dx, 0);
        int stepY = Double.compare(dy, 0);
        int stepZ = Double.compare(dz, 0);

        double tMaxX = boundary(ex, dx, cx, stepX);
        double tMaxY = boundary(ey, dy, cy, stepY);
        double tMaxZ = boundary(ez, dz, cz, stepZ);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        // The eye's own cell is skipped: the companion may have its head inside grass or a door.
        while (true) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                cx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                cy += stepY;
                tMaxY += tDeltaY;
            } else {
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (cx == tx && cy == ty && cz == tz) {
                return true;
            }
            Cell.Kind kind = view.at(cx, cy, cz).kind();
            if (kind == Cell.Kind.SOLID
                    || kind == Cell.Kind.PARTIAL
                    || kind == Cell.Kind.TALL
                    || kind == Cell.Kind.DOOR) {
                return false;
            }
            if (tMaxX > 1.0 + EPSILON && tMaxY > 1.0 + EPSILON && tMaxZ > 1.0 + EPSILON) {
                // Ran past the segment without entering the target cell (should not happen for an integer target).
                return false;
            }
        }
    }

    private static double boundary(double start, double delta, int cell, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double next = step > 0 ? cell + 1 : cell;
        return Math.abs((next - start) / delta);
    }
}
