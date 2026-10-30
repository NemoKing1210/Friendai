package dev.friendai.foundation.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Fits whole lines into a character budget. A tool result that is too large loses whole lines from the end and
 * reports that it was truncated, so a continuation id always points at a line the model actually received.
 */
public final class TextBudget {
    private TextBudget() {}

    public record Fit(List<String> lines, boolean truncated) {}

    public static Fit fit(List<String> lines, int maxChars) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(lines));
        if (maxChars <= 0) {
            return new Fit(List.copyOf(unique), false);
        }
        List<String> kept = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (String line : unique) {
            if (line == null) {
                continue;
            }
            int cost = kept.isEmpty() ? line.length() : line.length() + 1;
            if (used + cost > maxChars) {
                truncated = true;
                break;
            }
            kept.add(line);
            used += cost;
        }
        if (kept.isEmpty() && !unique.isEmpty() && unique.get(0) != null) {
            String first = unique.get(0);
            kept.add(first.length() <= maxChars ? first : first.substring(0, Math.max(0, maxChars - 1)) + "…");
            truncated = unique.size() > 1 || first.length() > maxChars;
        }
        return new Fit(List.copyOf(kept), truncated);
    }

    public static String join(Fit fit) {
        return String.join("\n", fit.lines());
    }
}
