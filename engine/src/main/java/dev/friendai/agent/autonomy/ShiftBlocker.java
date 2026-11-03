package dev.friendai.agent.autonomy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A stable key for what stopped a shift, so the same blocker is reported to the owner once even when the task's own
 * counters, coordinates or the tree it happened at differ between two attempts.
 */
public final class ShiftBlocker {
    private static final Pattern TICKS = Pattern.compile(" in \\d+ ticks?");
    private static final Pattern COUNTERS =
            Pattern.compile("; \\d+ (?:scaffold blocks? placed|logs? could not be cut)");
    private static final Pattern COORDS = Pattern.compile("-?\\d+ -?\\d+ -?\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ShiftBlocker() {}

    /** The normalized cause; two summaries with the same blocker produce the same key. */
    public static String key(String summary) {
        if (summary == null) {
            return "";
        }
        String text = summary.strip().toLowerCase(Locale.ROOT);
        int cut = text.length();
        var ticks = TICKS.matcher(text);
        if (ticks.find()) {
            cut = Math.min(cut, ticks.start());
        }
        var counters = COUNTERS.matcher(text);
        if (counters.find()) {
            cut = Math.min(cut, counters.start());
        }
        text = COORDS.matcher(text.substring(0, cut)).replaceAll("<pos>");
        return SPACES.matcher(text).replaceAll(" ").strip();
    }
}
