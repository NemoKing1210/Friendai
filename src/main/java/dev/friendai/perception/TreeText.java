package dev.friendai.perception;

import java.util.List;
import java.util.stream.Collectors;

/** Formats trees for the prompt and for the {@code find_trees} tool. English, LLM-facing. */
public final class TreeText {
    private TreeText() {}

    public static String perceptionLine(List<TreeSummary> trees) {
        return "Trees: " + trees.stream().map(TreeSummary::toPromptText).collect(Collectors.joining("; "));
    }

    /** Trees nearest first, then log structures, with the evidence scope named. */
    public static String toolListing(
            List<TreeSummary> trees, List<TreeSummary> structures, int radius, String provenance) {
        String listing = toolListing(trees, structures, radius);
        return provenance == null || provenance.isBlank() ? listing : provenance + ": " + listing;
    }

    /** Trees nearest first, then log structures; an empty result still says how far it looked. */
    public static String toolListing(List<TreeSummary> trees, List<TreeSummary> structures, int radius) {
        String within = " within " + radius + " blocks";
        if (trees.isEmpty()) {
            return structures.isEmpty()
                    ? "no trees" + within
                    : "no trees" + within + "; nearest " + structures.get(0).toListing();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(trees.size() == 1 ? "1 tree" : trees.size() + " trees")
                .append(within)
                .append(": ");
        sb.append(trees.stream().map(TreeSummary::toListing).collect(Collectors.joining("; ")));
        if (!structures.isEmpty()) {
            sb.append("; also ")
                    .append(structures.stream().map(TreeSummary::toListing).collect(Collectors.joining("; ")));
        }
        return sb.toString();
    }
}
