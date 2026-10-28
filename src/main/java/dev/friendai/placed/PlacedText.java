package dev.friendai.placed;

import dev.friendai.pathing.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Renders the prompt block and the tool listing for placed blocks. Pure: the facade supplies directions. */
public final class PlacedText {

    /** One material line: how many, where the nearest one is and what placed it. */
    public record Group(String blockId, int count, PlacedBlock nearest, String where) {
        public Group {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(nearest, "nearest");
            where = where == null ? "" : where.strip();
        }
    }

    private static final String PROMPT_HEADER = "Blocks you placed that are still in the world (nearest first):";

    private PlacedText() {}

    /** Groups nearest-first entries by material, keeping the order in which each material first appears. */
    public static List<Group> group(List<PlacedBlock> entries, Function<PlacedBlock, String> where) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(where, "where");
        Map<String, Group> groups = new LinkedHashMap<>();
        for (PlacedBlock block : entries) {
            Group existing = groups.get(block.blockId());
            if (existing == null) {
                groups.put(block.blockId(), new Group(block.blockId(), 1, block, where.apply(block)));
            } else {
                groups.put(
                        block.blockId(),
                        new Group(existing.blockId(), existing.count() + 1, existing.nearest(), existing.where()));
            }
        }
        return List.copyOf(groups.values());
    }

    /** The prompt block; an empty list renders nothing. Whole lines only, so a cut never leaves half a fact. */
    public static String prompt(List<Group> groups, int maxChars) {
        if (groups == null || groups.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(PROMPT_HEADER);
        for (Group group : groups) {
            String line = "\n- " + group.count() + " " + group.blockId() + ": "
                    + (group.where().isBlank() ? "" : "nearest " + group.where() + " ") + "at "
                    + group.nearest().pos().x() + " " + group.nearest().pos().y() + " "
                    + group.nearest().pos().z() + " (" + group.nearest().source() + ")";
            if (maxChars > 0 && out.length() + line.length() > maxChars) {
                break;
            }
            out.append(line);
        }
        return out.toString();
    }

    /**
     * The tool result: how many of how many, nearest first with coordinates, distance and source. A null
     * {@code label} means "blocks"; the radius is quoted in the sentence so the model knows the search bound.
     */
    public static String listing(PlacedQuery.Result result, String label, int radius, Pos origin) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(origin, "origin");
        String what = label == null || label.isBlank() ? "blocks" : label;
        if (result.total() == 0) {
            return "no " + what + " you placed within " + radius + " blocks";
        }
        StringBuilder out = new StringBuilder()
                .append(result.entries().size())
                .append(" of ")
                .append(result.total())
                .append(' ')
                .append(what)
                .append(" you placed within ")
                .append(radius)
                .append(" blocks nearest first");
        if (result.truncated()) {
            out.append(" (truncated)");
        }
        out.append(": ");
        for (int i = 0; i < result.entries().size(); i++) {
            PlacedBlock block = result.entries().get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append(block.pos().x())
                    .append(' ')
                    .append(block.pos().y())
                    .append(' ')
                    .append(block.pos().z())
                    .append(' ')
                    .append(block.blockId())
                    .append(" (")
                    .append(String.format(
                            Locale.ROOT, "%.1f", Math.sqrt(PlacedQuery.distanceSquared(origin, block.pos()))))
                    .append(" blocks, ")
                    .append(block.source())
                    .append(')');
        }
        return out.toString();
    }
}
