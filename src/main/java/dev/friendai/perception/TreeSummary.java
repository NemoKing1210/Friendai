package dev.friendai.perception;

/**
 * One tree-like cluster of logs as the prompt and the {@code find_trees} tool show it, with no Minecraft types.
 * {@code kind} is one of {@code standing}, {@code floating}, {@code stump}, {@code log structure}.
 */
public record TreeSummary(
        String type, String kind, String coords, int logs, int height, double distance, String direction) {

    /** The compact form for the perception line, without coordinates. */
    public String toPromptText() {
        return type + " " + distanceText() + " blocks " + direction + " (" + logs + " logs, height " + height + ", "
                + kind + ")";
    }

    /** The form for the tool result, with the base coordinates. */
    public String toListing() {
        return type + " at " + coords + " (" + distanceText() + " blocks " + direction + "), " + logs + " logs, height "
                + height + ", " + kind;
    }

    private String distanceText() {
        return PerceptionSnapshot.number((float) distance);
    }
}
