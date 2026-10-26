package dev.friendai.debug;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
        List<String> lines) {

    public static final int MAX_SEGMENTS = 400;
    public static final int MAX_CELLS = 200;
    public static final int MAX_LINES = 24;
    public static final int MAX_LINE_CHARS = 160;

    /** One step of the route; {@code move} is the ordinal of {@code dev.friendai.pathing.Move}. */
    public record Segment(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, int move) {}

    public record Cell(int x, int y, int z) {}

    public MovementDebug {
        route = List.copyOf(route);
        breaks = List.copyOf(breaks);
        places = List.copyOf(places);
        goalCells = List.copyOf(goalCells);
        lines = List.copyOf(lines);
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
                route.add(new Segment(in.readInt(), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt(), in.readUnsignedByte()));
            }
            List<Cell> breaks = readCells(in);
            List<Cell> places = readCells(in);
            List<Cell> goalCells = readCells(in);
            int lineCount = in.readUnsignedByte();
            if (lineCount > MAX_LINES) {
                throw new IllegalArgumentException("too many lines: " + lineCount);
            }
            List<String> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                lines.add(in.readUTF());
            }
            if (in.available() > 0) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new MovementDebug(companionId, stepIndex, route, breaks, places, goalCells, lines);
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
}
