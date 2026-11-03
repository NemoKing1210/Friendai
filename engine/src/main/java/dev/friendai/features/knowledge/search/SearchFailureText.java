package dev.friendai.features.knowledge.search;

/** The one line a search driver returns when its bounded mining leg gives up. */
public final class SearchFailureText {
    private static final int MAX = 600;

    private SearchFailureText() {}

    /**
     * Composes the leg's own result with the mining session's debug reason, so the model and the owner see whether
     * the leg broke blocks, found no route, or was refused for a tool or a budget.
     */
    public static String miningLeg(String targetLabel, String legSummary, String debugReason) {
        StringBuilder text =
                new StringBuilder("guarded mining leg ended without current visible evidence for ").append(targetLabel);
        if (legSummary != null && !legSummary.isBlank()) {
            text.append(": ").append(legSummary.trim());
        }
        if (debugReason != null && !debugReason.isBlank()) {
            text.append(" (").append(debugReason.trim()).append(')');
        }
        return text.length() <= MAX ? text.toString() : text.substring(0, MAX - 3) + "...";
    }
}
