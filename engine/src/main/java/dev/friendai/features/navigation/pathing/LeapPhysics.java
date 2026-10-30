package dev.friendai.features.navigation.pathing;

/**
 * The ballistics of a running or standing jump over a gap, with vanilla's numbers: the jump kicks the body up at
 * {@value #JUMP_VELOCITY} blocks per tick, gravity and vertical drag pull it back, and the horizontal speed decays by
 * the air drag every tick. The planner asks which gaps a body can clear and the executor asks what take-off speed a
 * given crossing needs, so both agree on what "a leap that lands" means.
 *
 * <p>Distances are measured from the take-off point, {@value #TAKE_OFF_OFFSET} blocks past the centre of the cell the
 * leap starts from (the executor waits for that run-up), to the centre of the landing cell.
 */
public final class LeapPhysics {
    /** The highest landing a leap reaches, in blocks above the take-off cell. */
    public static final int MAX_RISE = 1;
    /** The deepest landing a leap is planned for, in blocks below the take-off cell (the safe fall). */
    public static final int MAX_DROP = 3;
    /** Take-off speed of a standing or walking jump, in blocks per tick. */
    public static final double WALK_TAKEOFF = 0.25;
    /** Take-off speed of a sprinting jump, in blocks per tick. */
    public static final double SPRINT_TAKEOFF = 0.6;
    /** How far past the source cell's centre the body leaves the ground. */
    public static final double TAKE_OFF_OFFSET = 0.3;
    /** The share of the theoretical reach the planner counts on, so a crossing never lands on its last millimetre. */
    private static final double SAFETY = 0.92;

    private static final double JUMP_VELOCITY = 0.42;
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    /** The share of horizontal speed an airborne body keeps each tick. */
    public static final double AIR_DRAG = 0.91;

    private static final int MAX_AIR_TICKS = 40;

    /** Horizontal blocks flown per unit of take-off speed, indexed by {@code dy + MAX_DROP}. */
    private static final double[] FLIGHT = new double[MAX_RISE + MAX_DROP + 1];

    /** The widest gap, in empty cells, any landing height lets a sprinting body clear. */
    public static final int MAX_WIDTH;

    static {
        for (int dy = -MAX_DROP; dy <= MAX_RISE; dy++) {
            FLIGHT[dy + MAX_DROP] = simulate(dy);
        }
        int widest = 0;
        for (int dy = -MAX_DROP; dy <= MAX_RISE; dy++) {
            while (reachable(widest + 1, dy, true)) {
                widest++;
            }
        }
        MAX_WIDTH = widest;
    }

    private LeapPhysics() {}

    /**
     * Blocks flown horizontally per unit of take-off speed before the body is back down at {@code dy} cells above (or
     * below, when negative) the take-off level.
     */
    public static double flight(int dy) {
        return FLIGHT[dy + MAX_DROP];
    }

    /** The distance from the take-off point to the centre of the landing cell across {@code width} empty cells. */
    public static double aim(int width) {
        return width + 1.0 - TAKE_OFF_OFFSET;
    }

    /** Whether a body with this take-off ability can clear {@code width} empty cells and land {@code dy} higher. */
    public static boolean reachable(int width, int dy, boolean sprint) {
        if (dy < -MAX_DROP || dy > MAX_RISE) {
            return false;
        }
        return aim(width) <= cap(sprint) * flight(dy) * SAFETY;
    }

    /** Whether the crossing is beyond a standing or walking jump, so the body must sprint to clear it. */
    public static boolean needsSprint(int width, int dy) {
        return !reachable(width, dy, false);
    }

    /** The take-off speed that carries the body {@code distance} blocks before it lands {@code dy} cells higher. */
    public static double takeoffSpeed(double distance, int dy, boolean sprint) {
        return Math.min(cap(sprint), distance / flight(dy));
    }

    private static double cap(boolean sprint) {
        return sprint ? SPRINT_TAKEOFF : WALK_TAKEOFF;
    }

    private static double simulate(int dy) {
        double y = 0.0;
        double vy = JUMP_VELOCITY;
        double drag = 1.0;
        double flown = 0.0;
        for (int tick = 1; tick <= MAX_AIR_TICKS; tick++) {
            y += vy;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            flown += drag;
            drag *= AIR_DRAG;
            if (vy < 0.0 && y <= dy) {
                break;
            }
        }
        return flown;
    }
}
