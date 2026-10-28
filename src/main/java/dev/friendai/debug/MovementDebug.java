package dev.friendai.debug;

import dev.friendai.queue.Job;
import dev.friendai.queue.JobState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the movement debug overlay draws: the route the companion is walking, the cells it will break or fill, its goal
 * and a few lines of text. Built on the server from the navigator, sent as codec bytes and drawn by the client.
 */
public record MovementDebug(
        int companionId,
        int stepIndex,
        List<Segment> route,
        List<Cell> breaks,
        List<Cell> places,
        List<Cell> goalCells,
        List<String> lines,
        List<DebugTarget> targets,
        List<String> upcomingSteps) {

    public static final int MAX_SEGMENTS = 400;
    public static final int MAX_CELLS = 200;
    public static final int MAX_LINES = 24;
    public static final int MAX_LINE_CHARS = 160;
    public static final int MAX_TARGETS = 128;
    public static final int MAX_DETAIL_CHARS = 80;
    public static final int MAX_UPCOMING_STEPS = 2;

    /** Retained for callers that only provide the original movement view. */
    public MovementDebug(
            int companionId,
            int stepIndex,
            List<Segment> route,
            List<Cell> breaks,
            List<Cell> places,
            List<Cell> goalCells,
            List<String> lines) {
        this(companionId, stepIndex, route, breaks, places, goalCells, lines, List.of(), List.of());
    }

    /** One step of the route; {@code move} is the ordinal of {@code dev.friendai.pathing.Move}. */
    public record Segment(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, int move) {}

    public record Cell(int x, int y, int z) {}

    /** Combines task actions with path obstacles, keeping first-seen order and exact duplicates out. */
    public static List<DebugTarget> combineTargets(
            List<DebugTarget> taskTargets, List<Cell> routeBreaks, List<Cell> routePlaces) {
        Set<DebugTarget> seen = new LinkedHashSet<>();
        List<DebugTarget> result = new ArrayList<>(MAX_TARGETS);
        if (taskTargets != null) {
            for (DebugTarget target : taskTargets) {
                if (target != null && seen.add(target) && !appendTarget(result, target)) {
                    return List.copyOf(result);
                }
            }
        }
        if (routeBreaks != null) {
            for (Cell cell : routeBreaks) {
                DebugTarget target = new DebugTarget(cell.x(), cell.y(), cell.z(), DebugAction.BREAK, "Path obstacle");
                if (seen.add(target) && !appendTarget(result, target)) {
                    return List.copyOf(result);
                }
            }
        }
        if (routePlaces != null) {
            for (Cell cell : routePlaces) {
                DebugTarget target = new DebugTarget(cell.x(), cell.y(), cell.z(), DebugAction.PLACE, "Path support");
                if (seen.add(target) && !appendTarget(result, target)) {
                    return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean appendTarget(List<DebugTarget> targets, DebugTarget target) {
        targets.add(target);
        return targets.size() < MAX_TARGETS;
    }

    /** The next two pending queue jobs, ordered by their plan order. */
    public static List<String> upcomingSteps(List<Job> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return List.of();
        }
        return jobs.stream()
                .filter(job -> job.state() == JobState.PENDING)
                .sorted(Comparator.comparingLong(Job::order))
                .limit(MAX_UPCOMING_STEPS)
                .map(Job::tool)
                .map(tool -> tool.length() > MAX_LINE_CHARS ? tool.substring(0, MAX_LINE_CHARS) : tool)
                .toList();
    }

    public MovementDebug {
        route = List.copyOf(route);
        breaks = List.copyOf(breaks);
        places = List.copyOf(places);
        goalCells = List.copyOf(goalCells);
        lines = List.copyOf(lines);
        targets = List.copyOf(targets);
        upcomingSteps = List.copyOf(upcomingSteps);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(companionId);
            out.writeInt(stepIndex);
            List<Segment> segments = route.subList(0, Math.min(route.size(), MAX_SEGMENTS));
            out.writeShort(segments.size());
            for (Segment s : segments) {
                out.writeInt(s.fromX());
                out.writeInt(s.fromY());
                out.writeInt(s.fromZ());
                out.writeInt(s.toX());
                out.writeInt(s.toY());
                out.writeInt(s.toZ());
                out.writeByte(s.move());
            }
            writeCells(out, breaks);
            writeCells(out, places);
            writeCells(out, goalCells);
            writeTargets(out, targets);
            writeStrings(out, upcomingSteps, MAX_UPCOMING_STEPS, MAX_LINE_CHARS);
            List<String> text = lines.subList(0, Math.min(lines.size(), MAX_LINES));
            out.writeByte(text.size());
            for (String line : text) {
                out.writeUTF(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException when the bytes are not a well-formed view */
    public static MovementDebug decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int companionId = in.readInt();
            int stepIndex = in.readInt();
            int count = in.readUnsignedShort();
            if (count > MAX_SEGMENTS) {
                throw new IllegalArgumentException("too many route steps: " + count);
            }
            List<Segment> route = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                route.add(new Segment(
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readUnsignedByte()));
            }
            List<Cell> breaks = readCells(in);
            List<Cell> places = readCells(in);
            List<Cell> goalCells = readCells(in);
            List<DebugTarget> targets = readTargets(in);
            List<String> upcomingSteps = readStrings(in, MAX_UPCOMING_STEPS, MAX_LINE_CHARS, "upcoming steps");
            int lineCount = in.readUnsignedByte();
            if (lineCount > MAX_LINES) {
                throw new IllegalArgumentException("too many lines: " + lineCount);
            }
            List<String> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                String line = in.readUTF();
                if (line.length() > MAX_LINE_CHARS) {
                    throw new IllegalArgumentException("debug line is too long: " + line.length());
                }
                lines.add(line);
            }
            if (in.available() > 0) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new MovementDebug(
                    companionId, stepIndex, route, breaks, places, goalCells, lines, targets, upcomingSteps);
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated movement debug", e);
        }
    }

    private static void writeCells(DataOutputStream out, List<Cell> cells) throws IOException {
        List<Cell> kept = cells.subList(0, Math.min(cells.size(), MAX_CELLS));
        out.writeShort(kept.size());
        for (Cell c : kept) {
            out.writeInt(c.x());
            out.writeInt(c.y());
            out.writeInt(c.z());
        }
    }

    private static List<Cell> readCells(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > MAX_CELLS) {
            throw new IllegalArgumentException("too many cells: " + count);
        }
        List<Cell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cells.add(new Cell(in.readInt(), in.readInt(), in.readInt()));
        }
        return cells;
    }

    private static void writeTargets(DataOutputStream out, List<DebugTarget> targets) throws IOException {
        int count = Math.min(targets.size(), MAX_TARGETS);
        out.writeByte(count);
        for (int i = 0; i < count; i++) {
            DebugTarget target = targets.get(i);
            out.writeInt(target.x());
            out.writeInt(target.y());
            out.writeInt(target.z());
            out.writeByte(target.action().ordinal());
            String detail = target.detail();
            out.writeUTF(detail.length() > MAX_DETAIL_CHARS ? detail.substring(0, MAX_DETAIL_CHARS) : detail);
        }
    }

    private static List<DebugTarget> readTargets(DataInputStream in) throws IOException {
        int count = in.readUnsignedByte();
        if (count > MAX_TARGETS) {
            throw new IllegalArgumentException("too many action targets: " + count);
        }
        List<DebugTarget> targets = new ArrayList<>(count);
        DebugAction[] actions = DebugAction.values();
        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            int ordinal = in.readUnsignedByte();
            if (ordinal >= actions.length) {
                throw new IllegalArgumentException("unknown debug action: " + ordinal);
            }
            String detail = in.readUTF();
            if (detail.length() > MAX_DETAIL_CHARS) {
                throw new IllegalArgumentException("action detail is too long: " + detail.length());
            }
            targets.add(new DebugTarget(x, y, z, actions[ordinal], detail));
        }
        return targets;
    }

    private static void writeStrings(DataOutputStream out, List<String> values, int maxCount, int maxChars)
            throws IOException {
        int count = Math.min(values.size(), maxCount);
        out.writeByte(count);
        for (int i = 0; i < count; i++) {
            String value = values.get(i);
            value = value == null ? "" : value;
            out.writeUTF(value.length() > maxChars ? value.substring(0, maxChars) : value);
        }
    }

    private static List<String> readStrings(DataInputStream in, int maxCount, int maxChars, String label)
            throws IOException {
        int count = in.readUnsignedByte();
        if (count > maxCount) {
            throw new IllegalArgumentException("too many " + label + ": " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String value = in.readUTF();
            if (value.length() > maxChars) {
                throw new IllegalArgumentException(label + " text is too long: " + value.length());
            }
            values.add(value);
        }
        return values;
    }
}
