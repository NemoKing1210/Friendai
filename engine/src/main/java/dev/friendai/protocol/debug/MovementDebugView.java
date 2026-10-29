package dev.friendai.protocol.debug;

import dev.friendai.features.navigation.pathing.Move;
import java.util.List;

/** Pure engine representation of the operator-only movement overlay. */
public record MovementDebugView(
        int companionId,
        int stepIndex,
        List<Segment> route,
        List<Cell> breaks,
        List<Cell> places,
        List<Cell> goalCells,
        List<String> lines,
        List<Target> targets,
        List<String> upcomingSteps,
        List<WorkSet> workSets) {
    public static final int MAX_SEGMENTS = 400;
    public static final int MAX_CELLS = 200;
    public static final int MAX_LINES = 24;
    public static final int MAX_LINE_CHARS = 160;
    public static final int MAX_TARGETS = 128;
    public static final int MAX_DETAIL_CHARS = 80;
    public static final int MAX_UPCOMING_STEPS = 2;
    public static final int MAX_WORK_SETS = 8;
    public static final int MAX_WORK_CELLS = 256;
    public static final int MAX_CELLS_PER_SET = 256;

    public MovementDebugView {
        route = List.copyOf(route);
        breaks = List.copyOf(breaks);
        places = List.copyOf(places);
        goalCells = List.copyOf(goalCells);
        lines = List.copyOf(lines);
        targets = List.copyOf(targets);
        upcomingSteps = List.copyOf(upcomingSteps);
        workSets = List.copyOf(workSets);
    }

    public record Segment(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, Move move) {}

    public record Cell(int x, int y, int z) {}

    public enum Action {
        BREAK,
        PLACE,
        HARVEST,
        USE,
        CRAFT,
        SMELT,
        COLLECT
    }

    public record Target(int x, int y, int z, Action action, String detail) {}

    public enum WorkKind {
        BLOCKS,
        SECTORS,
        PLOT,
        BUILD
    }

    public enum WorkStatus {
        DONE,
        NEXT,
        PENDING,
        SKIPPED
    }

    public record WorkCell(int x, int y, int z, Action action, WorkStatus status, String detail) {}

    public record WorkSet(
            String label,
            WorkKind kind,
            int total,
            int done,
            int next,
            int pending,
            int skipped,
            boolean truncated,
            List<WorkCell> cells) {
        public WorkSet {
            cells = List.copyOf(cells);
        }
    }
}
