package dev.friendai.features.survival;

/**
 * A companion's experience points and level, using vanilla's level thresholds.
 *
 * <p>Pure Java: the entity feeds it orb values and spends levels; the model knows nothing about the world.
 */
public final class ExperienceModel {
    /** Far above any reachable level, and low enough that every sum stays inside an int. */
    public static final int MAX_POINTS = 1_000_000;

    private int points;
    private int level;

    /** Total points at the start of {@code level}, as vanilla counts them. */
    public static int pointsForLevel(int level) {
        long l = Math.max(0, level);
        if (l <= 16) {
            return (int) (l * l + 6 * l);
        }
        if (l <= 31) {
            return (int) (2.5 * l * l - 40.5 * l + 360);
        }
        return (int) (4.5 * l * l - 162.5 * l + 2220);
    }

    /** Points needed to climb from {@code level} to the next one. */
    public static int pointsToNextLevel(int level) {
        long l = Math.max(0, level);
        if (l >= 30) {
            return (int) (9 * l - 158);
        }
        if (l >= 15) {
            return (int) (5 * l - 38);
        }
        return (int) (2 * l + 7);
    }

    public int points() {
        return points;
    }

    public int level() {
        return level;
    }

    /** How far through the current level the points are, from 0 up to (excluding) 1. */
    public float progress() {
        return (points - pointsForLevel(level)) / (float) pointsToNextLevel(level);
    }

    /** Adds points; zero and negative values are ignored, the total is capped at {@link #MAX_POINTS}. */
    public void add(int amount) {
        if (amount <= 0) {
            return;
        }
        set((int) Math.min((long) points + amount, MAX_POINTS));
    }

    /** Spends whole levels like an anvil does: the points drop to the start of the lower level. */
    public boolean spendLevels(int n) {
        if (n < 0 || n > level) {
            return false;
        }
        set(pointsForLevel(level - n));
        return true;
    }

    /** Restores saved points, clamped to the valid range. */
    public void load(int saved) {
        set(Math.max(0, Math.min(saved, MAX_POINTS)));
    }

    /** Restores from a level and the progress inside it, the way a player entity stores them. */
    public void loadLevel(int savedLevel, float savedProgress) {
        int base = Math.max(0, savedLevel);
        float fraction = Math.max(0.0f, Math.min(savedProgress, 1.0f));
        long total = (long) pointsForLevel(base) + Math.round(fraction * pointsToNextLevel(base));
        load((int) Math.min(total, MAX_POINTS));
    }

    private void set(int value) {
        points = value;
        int l = 0;
        while (pointsForLevel(l + 1) <= points) {
            l++;
        }
        level = l;
    }
}
