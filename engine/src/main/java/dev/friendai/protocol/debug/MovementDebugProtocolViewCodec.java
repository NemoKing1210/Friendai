package dev.friendai.protocol.debug;

import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolBodyReader;
import dev.friendai.protocol.ProtocolBodyWriter;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewBodies;
import java.util.ArrayList;
import java.util.List;

/** Bounded movement-debug view codec with fixed operation and enum IDs. */
public final class MovementDebugProtocolViewCodec {
    public static final int MAX_FRAME_BYTES = 65_536;
    private static final int MAX_TEXT_BYTES = 640;
    private static final int MAX_DETAIL_BYTES = 320;

    private MovementDebugProtocolViewCodec() {}

    public static byte[] encode(MovementDebugView view) {
        return encode(view, 0);
    }

    public static byte[] encode(MovementDebugView view, long requestId) {
        MovementDebugView candidate = view;
        while (true) {
            try {
                return encodeFrame(candidate, requestId);
            } catch (ProtocolDataException tooLarge) {
                if (tooLarge.reason() != DecodeResult.Reason.OVERSIZED_FRAME) throw tooLarge;
                MovementDebugView smaller = trim(candidate);
                if (smaller == candidate) throw tooLarge;
                candidate = smaller;
            }
        }
    }

    private static byte[] encodeFrame(MovementDebugView view, long requestId) {
        ProtocolBodyWriter out = new ProtocolBodyWriter(MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
        out.writeInt(view.companionId());
        out.writeInt(view.stepIndex());
        List<MovementDebugView.Segment> route = prefix(view.route(), MovementDebugView.MAX_SEGMENTS);
        ProtocolViewBodies.writeCount(out, route.size(), MovementDebugView.MAX_SEGMENTS);
        for (MovementDebugView.Segment segment : route) {
            out.writeInt(segment.fromX());
            out.writeInt(segment.fromY());
            out.writeInt(segment.fromZ());
            out.writeInt(segment.toX());
            out.writeInt(segment.toY());
            out.writeInt(segment.toZ());
            out.writeUnsignedByte(moveId(segment.move()));
        }
        writeCells(out, view.breaks());
        writeCells(out, view.places());
        writeCells(out, view.goalCells());
        writeTargets(out, view.targets());
        writeStrings(out, view.upcomingSteps(), MovementDebugView.MAX_UPCOMING_STEPS, MAX_TEXT_BYTES);
        writeStrings(out, view.lines(), MovementDebugView.MAX_LINES, MAX_TEXT_BYTES);
        writeWorkSets(out, view.workSets());
        return ProtocolViewBodies.encode(ProtocolFamily.DEBUG, 129, requestId, out.toByteArray());
    }

    public static DecodeResult<ProtocolView<MovementDebugView>> decode(byte[] encoded) {
        return ProtocolViewBodies.decode(
                encoded, ProtocolFamily.DEBUG, 129, MovementDebugProtocolViewCodec::decodeBody);
    }

    private static MovementDebugView decodeBody(ProtocolBodyReader in) {
        int companionId = in.readInt();
        int stepIndex = in.readInt();
        int routeCount = ProtocolViewBodies.readCount(in, MovementDebugView.MAX_SEGMENTS);
        List<MovementDebugView.Segment> route = new ArrayList<>(routeCount);
        for (int i = 0; i < routeCount; i++) {
            int fromX = in.readInt();
            int fromY = in.readInt();
            int fromZ = in.readInt();
            int toX = in.readInt();
            int toY = in.readInt();
            int toZ = in.readInt();
            route.add(new MovementDebugView.Segment(fromX, fromY, fromZ, toX, toY, toZ, move(in.readUnsignedByte())));
        }
        List<MovementDebugView.Cell> breaks = readCells(in);
        List<MovementDebugView.Cell> places = readCells(in);
        List<MovementDebugView.Cell> goals = readCells(in);
        List<MovementDebugView.Target> targets = readTargets(in);
        List<String> upcoming = readStrings(in, MovementDebugView.MAX_UPCOMING_STEPS, MAX_TEXT_BYTES);
        List<String> lines = readStrings(in, MovementDebugView.MAX_LINES, MAX_TEXT_BYTES);
        List<MovementDebugView.WorkSet> workSets = readWorkSets(in);
        return new MovementDebugView(
                companionId, stepIndex, route, breaks, places, goals, lines, targets, upcoming, workSets);
    }

    private static void writeCells(ProtocolBodyWriter out, List<MovementDebugView.Cell> cells) {
        List<MovementDebugView.Cell> kept = prefix(cells, MovementDebugView.MAX_CELLS);
        ProtocolViewBodies.writeCount(out, kept.size(), MovementDebugView.MAX_CELLS);
        for (MovementDebugView.Cell cell : kept) {
            out.writeInt(cell.x());
            out.writeInt(cell.y());
            out.writeInt(cell.z());
        }
    }

    private static List<MovementDebugView.Cell> readCells(ProtocolBodyReader in) {
        int count = ProtocolViewBodies.readCount(in, MovementDebugView.MAX_CELLS);
        List<MovementDebugView.Cell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) cells.add(new MovementDebugView.Cell(in.readInt(), in.readInt(), in.readInt()));
        return cells;
    }

    private static void writeTargets(ProtocolBodyWriter out, List<MovementDebugView.Target> targets) {
        List<MovementDebugView.Target> kept = prefix(targets, MovementDebugView.MAX_TARGETS);
        out.writeUnsignedByte(kept.size());
        for (MovementDebugView.Target target : kept) {
            out.writeInt(target.x());
            out.writeInt(target.y());
            out.writeInt(target.z());
            out.writeUnsignedByte(actionId(target.action()));
            out.writeString(clip(target.detail(), MovementDebugView.MAX_DETAIL_CHARS), MAX_DETAIL_BYTES);
        }
    }

    private static List<MovementDebugView.Target> readTargets(ProtocolBodyReader in) {
        int count = in.readUnsignedByte();
        if (count > MovementDebugView.MAX_TARGETS) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        List<MovementDebugView.Target> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(new MovementDebugView.Target(
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    action(in.readUnsignedByte()),
                    readBoundedText(in, MAX_DETAIL_BYTES, MovementDebugView.MAX_DETAIL_CHARS)));
        }
        return targets;
    }

    private static void writeStrings(ProtocolBodyWriter out, List<String> values, int maxCount, int maxBytes) {
        List<String> kept = prefix(values, maxCount);
        out.writeUnsignedByte(kept.size());
        for (String value : kept) out.writeString(clip(value, maxBytes / 4), maxBytes);
    }

    private static List<String> readStrings(ProtocolBodyReader in, int maxCount, int maxBytes) {
        int count = in.readUnsignedByte();
        if (count > maxCount) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readBoundedText(in, maxBytes, maxBytes / 4));
        return values;
    }

    private static void writeWorkSets(ProtocolBodyWriter out, List<MovementDebugView.WorkSet> workSets) {
        List<MovementDebugView.WorkSet> kept = prefix(workSets, MovementDebugView.MAX_WORK_SETS);
        out.writeUnsignedByte(kept.size());
        int totalCells = 0;
        for (MovementDebugView.WorkSet workSet : kept) {
            out.writeString(clip(workSet.label(), MovementDebugView.MAX_DETAIL_CHARS), MAX_TEXT_BYTES);
            out.writeUnsignedByte(workKindId(workSet.kind()));
            validateWorkCounts(workSet.total(), workSet.done(), workSet.next(), workSet.pending(), workSet.skipped());
            out.writeInt(workSet.total());
            out.writeInt(workSet.done());
            out.writeInt(workSet.next());
            out.writeInt(workSet.pending());
            out.writeInt(workSet.skipped());
            out.writeBoolean(workSet.truncated() || workSets.size() > kept.size());
            int count = Math.min(
                    Math.min(workSet.cells().size(), MovementDebugView.MAX_CELLS_PER_SET),
                    MovementDebugView.MAX_WORK_CELLS - totalCells);
            validateSetCells(
                    workSet.cells().subList(0, count),
                    workSet.done(),
                    workSet.next(),
                    workSet.pending(),
                    workSet.skipped());
            ProtocolViewBodies.writeCount(out, count, MovementDebugView.MAX_CELLS_PER_SET);
            for (int i = 0; i < count; i++) {
                MovementDebugView.WorkCell cell = workSet.cells().get(i);
                out.writeInt(cell.x());
                out.writeInt(cell.y());
                out.writeInt(cell.z());
                out.writeUnsignedByte(actionId(cell.action()));
                out.writeUnsignedByte(workStatusId(cell.status()));
                out.writeString(clip(cell.detail(), MovementDebugView.MAX_DETAIL_CHARS), MAX_DETAIL_BYTES);
            }
            totalCells += count;
        }
    }

    private static List<MovementDebugView.WorkSet> readWorkSets(ProtocolBodyReader in) {
        int setCount = in.readUnsignedByte();
        if (setCount > MovementDebugView.MAX_WORK_SETS)
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        List<MovementDebugView.WorkSet> sets = new ArrayList<>(setCount);
        int totalCells = 0;
        for (int i = 0; i < setCount; i++) {
            String label = readBoundedText(in, MAX_TEXT_BYTES, MovementDebugView.MAX_DETAIL_CHARS);
            MovementDebugView.WorkKind kind = workKind(in.readUnsignedByte());
            int total = in.readInt();
            int done = in.readInt();
            int next = in.readInt();
            int pending = in.readInt();
            int skipped = in.readInt();
            boolean truncated = in.readBoolean();
            validateWorkCounts(total, done, next, pending, skipped);
            int count = ProtocolViewBodies.readCount(in, MovementDebugView.MAX_CELLS_PER_SET);
            totalCells += count;
            if (totalCells > MovementDebugView.MAX_WORK_CELLS)
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            List<MovementDebugView.WorkCell> cells = new ArrayList<>(count);
            for (int j = 0; j < count; j++) {
                cells.add(new MovementDebugView.WorkCell(
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        action(in.readUnsignedByte()),
                        workStatus(in.readUnsignedByte()),
                        readBoundedText(in, MAX_DETAIL_BYTES, MovementDebugView.MAX_DETAIL_CHARS)));
            }
            validateSetCells(cells, done, next, pending, skipped);
            sets.add(new MovementDebugView.WorkSet(label, kind, total, done, next, pending, skipped, truncated, cells));
        }
        return sets;
    }

    private static void validateWorkCounts(int total, int done, int next, int pending, int skipped) {
        if (total < 0
                || done < 0
                || next < 0
                || pending < 0
                || skipped < 0
                || (long) done + next + pending + skipped != total)
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
    }

    private static void validateSetCells(
            List<MovementDebugView.WorkCell> cells, int done, int next, int pending, int skipped) {
        int cellDone = 0;
        int cellNext = 0;
        int cellPending = 0;
        int cellSkipped = 0;
        for (MovementDebugView.WorkCell cell : cells) {
            switch (cell.status()) {
                case DONE -> cellDone++;
                case NEXT -> cellNext++;
                case PENDING -> cellPending++;
                case SKIPPED -> cellSkipped++;
            }
        }
        if (cellDone > done || cellNext > next || cellPending > pending || cellSkipped > skipped)
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
    }

    private static int moveId(Move move) {
        return switch (move) {
            case WALK -> 1;
            case WALK_DIAGONAL -> 2;
            case ASCEND -> 3;
            case DESCEND -> 4;
            case JUMP_GAP -> 5;
            case SWIM -> 6;
            case CLIMB -> 7;
            case DOOR -> 8;
            case DIG -> 9;
            case DIG_ASCEND -> 10;
            case DIG_DESCEND -> 11;
            case PILLAR -> 12;
            case BRIDGE -> 13;
        };
    }

    private static Move move(int id) {
        return switch (id) {
            case 1 -> Move.WALK;
            case 2 -> Move.WALK_DIAGONAL;
            case 3 -> Move.ASCEND;
            case 4 -> Move.DESCEND;
            case 5 -> Move.JUMP_GAP;
            case 6 -> Move.SWIM;
            case 7 -> Move.CLIMB;
            case 8 -> Move.DOOR;
            case 9 -> Move.DIG;
            case 10 -> Move.DIG_ASCEND;
            case 11 -> Move.DIG_DESCEND;
            case 12 -> Move.PILLAR;
            case 13 -> Move.BRIDGE;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static int actionId(MovementDebugView.Action action) {
        return switch (action) {
            case BREAK -> 1;
            case PLACE -> 2;
            case HARVEST -> 3;
            case USE -> 4;
            case CRAFT -> 5;
            case SMELT -> 6;
            case COLLECT -> 7;
        };
    }

    private static MovementDebugView.Action action(int id) {
        return switch (id) {
            case 1 -> MovementDebugView.Action.BREAK;
            case 2 -> MovementDebugView.Action.PLACE;
            case 3 -> MovementDebugView.Action.HARVEST;
            case 4 -> MovementDebugView.Action.USE;
            case 5 -> MovementDebugView.Action.CRAFT;
            case 6 -> MovementDebugView.Action.SMELT;
            case 7 -> MovementDebugView.Action.COLLECT;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static int workKindId(MovementDebugView.WorkKind kind) {
        return switch (kind) {
            case BLOCKS -> 1;
            case SECTORS -> 2;
            case PLOT -> 3;
            case BUILD -> 4;
        };
    }

    private static MovementDebugView.WorkKind workKind(int id) {
        return switch (id) {
            case 1 -> MovementDebugView.WorkKind.BLOCKS;
            case 2 -> MovementDebugView.WorkKind.SECTORS;
            case 3 -> MovementDebugView.WorkKind.PLOT;
            case 4 -> MovementDebugView.WorkKind.BUILD;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static int workStatusId(MovementDebugView.WorkStatus status) {
        return switch (status) {
            case DONE -> 1;
            case NEXT -> 2;
            case PENDING -> 3;
            case SKIPPED -> 4;
        };
    }

    private static MovementDebugView.WorkStatus workStatus(int id) {
        return switch (id) {
            case 1 -> MovementDebugView.WorkStatus.DONE;
            case 2 -> MovementDebugView.WorkStatus.NEXT;
            case 3 -> MovementDebugView.WorkStatus.PENDING;
            case 4 -> MovementDebugView.WorkStatus.SKIPPED;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static <T> List<T> prefix(List<T> values, int max) {
        return values.subList(0, Math.min(values.size(), max));
    }

    private static String readBoundedText(ProtocolBodyReader in, int maxBytes, int maxChars) {
        String value = in.readString(maxBytes);
        if (value.length() > maxChars) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        return value;
    }

    private static String clip(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private static MovementDebugView trim(MovementDebugView view) {
        List<MovementDebugView.WorkSet> workSets =
                new ArrayList<>(prefix(view.workSets(), MovementDebugView.MAX_WORK_SETS));
        if (view.workSets().size() > workSets.size() && !workSets.isEmpty()) {
            MovementDebugView.WorkSet last = workSets.getLast();
            workSets.set(
                    workSets.size() - 1,
                    new MovementDebugView.WorkSet(
                            last.label(),
                            last.kind(),
                            last.total(),
                            last.done(),
                            last.next(),
                            last.pending(),
                            last.skipped(),
                            true,
                            last.cells()));
        }
        int workCellCount =
                workSets.stream().mapToInt(set -> set.cells().size()).sum();
        if (workCellCount > 0) {
            int keep = workCellCount / 2;
            int remaining = keep;
            List<MovementDebugView.WorkSet> reduced = new ArrayList<>(workSets.size());
            for (MovementDebugView.WorkSet set : workSets) {
                int count = Math.min(set.cells().size(), remaining);
                reduced.add(new MovementDebugView.WorkSet(
                        set.label(),
                        set.kind(),
                        set.total(),
                        set.done(),
                        set.next(),
                        set.pending(),
                        set.skipped(),
                        true,
                        List.copyOf(set.cells().subList(0, count))));
                remaining -= count;
            }
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places(),
                    view.goalCells(),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps(),
                    reduced);
        }
        if (view.targets().size() > 0)
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places(),
                    view.goalCells(),
                    view.lines(),
                    view.targets().subList(0, view.targets().size() / 2),
                    view.upcomingSteps(),
                    workSets);
        if (view.lines().size() > 0)
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places(),
                    view.goalCells(),
                    view.lines().subList(0, view.lines().size() / 2),
                    view.targets(),
                    view.upcomingSteps(),
                    workSets);
        if (view.upcomingSteps().size() > 0)
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places(),
                    view.goalCells(),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps().subList(0, view.upcomingSteps().size() / 2),
                    workSets);
        if (!view.breaks().isEmpty())
            return copy(
                    view,
                    view.route(),
                    view.breaks().subList(0, view.breaks().size() / 2),
                    view.places(),
                    view.goalCells(),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps(),
                    workSets);
        if (!view.places().isEmpty())
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places().subList(0, view.places().size() / 2),
                    view.goalCells(),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps(),
                    workSets);
        if (!view.goalCells().isEmpty())
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places(),
                    view.goalCells().subList(0, view.goalCells().size() / 2),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps(),
                    workSets);
        if (!view.route().isEmpty())
            return copy(
                    view,
                    view.route().subList(0, view.route().size() / 2),
                    view.breaks(),
                    view.places(),
                    view.goalCells(),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps(),
                    workSets);
        if (view.workSets().size() > MovementDebugView.MAX_WORK_SETS)
            return copy(
                    view,
                    view.route(),
                    view.breaks(),
                    view.places(),
                    view.goalCells(),
                    view.lines(),
                    view.targets(),
                    view.upcomingSteps(),
                    markLastTruncated(view.workSets().subList(0, view.workSets().size() / 2)));
        return view;
    }

    private static List<MovementDebugView.WorkSet> markLastTruncated(List<MovementDebugView.WorkSet> workSets) {
        List<MovementDebugView.WorkSet> result = new ArrayList<>(workSets);
        if (!result.isEmpty()) {
            MovementDebugView.WorkSet last = result.getLast();
            result.set(
                    result.size() - 1,
                    new MovementDebugView.WorkSet(
                            last.label(),
                            last.kind(),
                            last.total(),
                            last.done(),
                            last.next(),
                            last.pending(),
                            last.skipped(),
                            true,
                            last.cells()));
        }
        return List.copyOf(result);
    }

    private static MovementDebugView copy(
            MovementDebugView original,
            List<MovementDebugView.Segment> route,
            List<MovementDebugView.Cell> breaks,
            List<MovementDebugView.Cell> places,
            List<MovementDebugView.Cell> goalCells,
            List<String> lines,
            List<MovementDebugView.Target> targets,
            List<String> upcomingSteps,
            List<MovementDebugView.WorkSet> workSets) {
        return new MovementDebugView(
                original.companionId(),
                original.stepIndex(),
                route,
                breaks,
                places,
                goalCells,
                lines,
                targets,
                upcomingSteps,
                workSets);
    }
}
