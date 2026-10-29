package dev.friendai.transport.protocol;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.MovementDebug;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.protocol.debug.MovementDebugView;
import java.util.List;

/** Bridges the Minecraft-facing debug model to its engine-owned protocol representation. */
public final class MovementDebugProtocolAdapter {
    private MovementDebugProtocolAdapter() {}

    public static MovementDebugView toProtocol(MovementDebug view) {
        List<MovementDebugView.Segment> route = view.route().stream()
                .map(segment -> new MovementDebugView.Segment(
                        segment.fromX(),
                        segment.fromY(),
                        segment.fromZ(),
                        segment.toX(),
                        segment.toY(),
                        segment.toZ(),
                        move(segment.move())))
                .toList();
        List<MovementDebugView.Cell> breaks = view.breaks().stream()
                .map(cell -> new MovementDebugView.Cell(cell.x(), cell.y(), cell.z()))
                .toList();
        List<MovementDebugView.Cell> places = view.places().stream()
                .map(cell -> new MovementDebugView.Cell(cell.x(), cell.y(), cell.z()))
                .toList();
        List<MovementDebugView.Cell> goals = view.goalCells().stream()
                .map(cell -> new MovementDebugView.Cell(cell.x(), cell.y(), cell.z()))
                .toList();
        List<MovementDebugView.Target> targets = view.targets().stream()
                .map(target -> new MovementDebugView.Target(
                        target.x(), target.y(), target.z(), action(target.action()), target.detail()))
                .toList();
        List<MovementDebugView.WorkSet> workSets = view.workSets().stream()
                .map(MovementDebugProtocolAdapter::workSet)
                .toList();
        return new MovementDebugView(
                view.companionId(),
                view.stepIndex(),
                route,
                breaks,
                places,
                goals,
                view.lines(),
                targets,
                view.upcomingSteps(),
                workSets);
    }

    public static MovementDebug fromProtocol(MovementDebugView view) {
        List<MovementDebug.Segment> route = view.route().stream()
                .map(segment -> new MovementDebug.Segment(
                        segment.fromX(),
                        segment.fromY(),
                        segment.fromZ(),
                        segment.toX(),
                        segment.toY(),
                        segment.toZ(),
                        segment.move().ordinal()))
                .toList();
        List<MovementDebug.Cell> breaks = view.breaks().stream()
                .map(cell -> new MovementDebug.Cell(cell.x(), cell.y(), cell.z()))
                .toList();
        List<MovementDebug.Cell> places = view.places().stream()
                .map(cell -> new MovementDebug.Cell(cell.x(), cell.y(), cell.z()))
                .toList();
        List<MovementDebug.Cell> goals = view.goalCells().stream()
                .map(cell -> new MovementDebug.Cell(cell.x(), cell.y(), cell.z()))
                .toList();
        List<DebugTarget> targets = view.targets().stream()
                .map(target ->
                        new DebugTarget(target.x(), target.y(), target.z(), action(target.action()), target.detail()))
                .toList();
        List<WorkSet> workSets = view.workSets().stream()
                .map(MovementDebugProtocolAdapter::workSet)
                .toList();
        return new MovementDebug(
                view.companionId(),
                view.stepIndex(),
                route,
                breaks,
                places,
                goals,
                view.lines(),
                targets,
                view.upcomingSteps(),
                workSets);
    }

    private static Move move(int id) {
        return switch (id) {
            case 0 -> Move.WALK;
            case 1 -> Move.WALK_DIAGONAL;
            case 2 -> Move.ASCEND;
            case 3 -> Move.DESCEND;
            case 4 -> Move.JUMP_GAP;
            case 5 -> Move.SWIM;
            case 6 -> Move.CLIMB;
            case 7 -> Move.DOOR;
            case 8 -> Move.DIG;
            case 9 -> Move.DIG_ASCEND;
            case 10 -> Move.DIG_DESCEND;
            case 11 -> Move.PILLAR;
            case 12 -> Move.BRIDGE;
            default -> throw new IllegalArgumentException("unknown movement action");
        };
    }

    private static MovementDebugView.Action action(DebugAction action) {
        return switch (action) {
            case BREAK -> MovementDebugView.Action.BREAK;
            case PLACE -> MovementDebugView.Action.PLACE;
            case HARVEST -> MovementDebugView.Action.HARVEST;
            case USE -> MovementDebugView.Action.USE;
            case CRAFT -> MovementDebugView.Action.CRAFT;
            case SMELT -> MovementDebugView.Action.SMELT;
            case COLLECT -> MovementDebugView.Action.COLLECT;
        };
    }

    private static DebugAction action(MovementDebugView.Action action) {
        return switch (action) {
            case BREAK -> DebugAction.BREAK;
            case PLACE -> DebugAction.PLACE;
            case HARVEST -> DebugAction.HARVEST;
            case USE -> DebugAction.USE;
            case CRAFT -> DebugAction.CRAFT;
            case SMELT -> DebugAction.SMELT;
            case COLLECT -> DebugAction.COLLECT;
        };
    }

    private static MovementDebugView.WorkSet workSet(WorkSet set) {
        return new MovementDebugView.WorkSet(
                set.label(),
                switch (set.kind()) {
                    case BLOCKS -> MovementDebugView.WorkKind.BLOCKS;
                    case SECTORS -> MovementDebugView.WorkKind.SECTORS;
                    case PLOT -> MovementDebugView.WorkKind.PLOT;
                    case BUILD -> MovementDebugView.WorkKind.BUILD;
                },
                set.total(),
                set.done(),
                set.next(),
                set.pending(),
                set.skipped(),
                set.truncated(),
                set.cells().stream()
                        .map(cell -> new MovementDebugView.WorkCell(
                                cell.x(),
                                cell.y(),
                                cell.z(),
                                action(cell.action()),
                                switch (cell.status()) {
                                    case DONE -> MovementDebugView.WorkStatus.DONE;
                                    case NEXT -> MovementDebugView.WorkStatus.NEXT;
                                    case PENDING -> MovementDebugView.WorkStatus.PENDING;
                                    case SKIPPED -> MovementDebugView.WorkStatus.SKIPPED;
                                },
                                cell.detail()))
                        .toList());
    }

    private static WorkSet workSet(MovementDebugView.WorkSet set) {
        return new WorkSet(
                set.label(),
                switch (set.kind()) {
                    case BLOCKS -> WorkKind.BLOCKS;
                    case SECTORS -> WorkKind.SECTORS;
                    case PLOT -> WorkKind.PLOT;
                    case BUILD -> WorkKind.BUILD;
                },
                set.total(),
                set.done(),
                set.next(),
                set.pending(),
                set.skipped(),
                set.truncated(),
                set.cells().stream()
                        .map(cell -> new WorkCell(
                                cell.x(),
                                cell.y(),
                                cell.z(),
                                action(cell.action()),
                                switch (cell.status()) {
                                    case DONE -> WorkStatus.DONE;
                                    case NEXT -> WorkStatus.NEXT;
                                    case PENDING -> WorkStatus.PENDING;
                                    case SKIPPED -> WorkStatus.SKIPPED;
                                },
                                cell.detail()))
                        .toList());
    }
}
