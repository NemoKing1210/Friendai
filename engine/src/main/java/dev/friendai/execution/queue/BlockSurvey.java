package dev.friendai.execution.queue;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, resumable survey of one cube: does it hold at least {@code count} matching cells?
 *
 * <p>Pure. The caller supplies loading and matching per cell, owns the pass budget and decides how long a completed
 * answer is trusted. A survey that runs out of budget keeps its cursor, so the next pass continues instead of
 * starting over; a cube with a cell the caller cannot read never yields {@link Outcome#ABSENT}, because absence is
 * only known once every cell has been seen.
 */
public final class BlockSurvey {
    /** What one survey may ask about a single cell. */
    public interface Cells {
        boolean loaded(int x, int y, int z);

        boolean matches(int x, int y, int z);
    }

    public enum Outcome {
        /** At least {@code count} matching cells were found. */
        MATCHED,
        /** Every cell was read and fewer than {@code count} matched. */
        ABSENT,
        /** The cube was not fully observed: the budget ran out or a cell could not be read. */
        INCOMPLETE
    }

    /** The outcome and how many cells this call inspected, so a caller can share one budget across surveys. */
    public record Result(Outcome outcome, int inspected) {}

    private final int originX, originY, originZ, radius, count;
    private final int size, volume;
    private final List<int[]> found = new ArrayList<>();
    private int cursor;
    private boolean sawUnloaded;

    public BlockSurvey(int originX, int originY, int originZ, int radius, int count) {
        if (radius < 1 || radius > 32 || count < 1 || count > 256) {
            throw new IllegalArgumentException("block survey bounds");
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.radius = radius;
        this.count = count;
        this.size = radius * 2 + 1;
        this.volume = size * size * size;
    }

    /** Walks the cube from where the last call stopped, spending at most {@code budget} cell reads. */
    public Result advance(Cells cells, int budget) {
        int inspected = 0;
        while (cursor < volume) {
            if (inspected >= budget) return new Result(Outcome.INCOMPLETE, inspected);
            int index = cursor++;
            inspected++;
            int x = originX + index / (size * size) - radius;
            int y = originY + index / size % size - radius;
            int z = originZ + index % size - radius;
            if (!cells.loaded(x, y, z)) {
                // A cell the caller cannot read is missing evidence, not an empty one.
                sawUnloaded = true;
                continue;
            }
            if (cells.matches(x, y, z)) {
                found.add(new int[] {x, y, z});
                if (found.size() >= count) {
                    cursor = 0;
                    sawUnloaded = false;
                    return new Result(Outcome.MATCHED, inspected);
                }
            }
        }
        cursor = 0;
        if (sawUnloaded) {
            sawUnloaded = false;
            return new Result(Outcome.INCOMPLETE, inspected);
        }
        return new Result(Outcome.ABSENT, inspected);
    }

    /** Whether a completed match still holds: every remembered cell must still be readable and matching. */
    public boolean stillMatches(Cells cells) {
        for (int[] cell : found) {
            if (!cells.loaded(cell[0], cell[1], cell[2]) || !cells.matches(cell[0], cell[1], cell[2])) {
                return false;
            }
        }
        return true;
    }

    /** Whether a previous call already found the requested number of cells. */
    public boolean complete() {
        return found.size() >= count;
    }

    /** Forgets both the answer and the scan progress, so the next call measures the cube from its first cell. */
    public void reset() {
        found.clear();
        cursor = 0;
        sawUnloaded = false;
    }
}
