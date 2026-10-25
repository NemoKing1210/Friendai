package dev.friendai.debug;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The structured form of the queue text the snapshot carries as {@code currentTask}
 * ({@code Task queue: N running, M pending[, K blocked]; plans: P} plus optional Running/Next/Blocked lines),
 * so the menu can draw it as counters and rows instead of one run of text.
 */
public record QueueSummary(int running, int pending, int blocked, int plans, String runningLine, String nextLine, String blockedLine) {
    private static final Pattern HEAD =
            Pattern.compile("Task queue: ([0-9]+) running, ([0-9]+) pending(?:, ([0-9]+) blocked)?; plans: ([0-9]+)");

    /** Empty when the text is not a queue summary (an idle companion or a single plain task). */
    public static Optional<QueueSummary> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] lines = text.split("\n");
        Matcher head = HEAD.matcher(lines[0]);
        if (!head.matches()) {
            return Optional.empty();
        }
        String running = "";
        String next = "";
        String blocked = "";
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("Running: ")) {
                running = lines[i].substring("Running: ".length());
            } else if (lines[i].startsWith("Next: ")) {
                next = lines[i].substring("Next: ".length());
            } else if (lines[i].startsWith("Blocked: ")) {
                blocked = lines[i].substring("Blocked: ".length());
            }
        }
        return Optional.of(new QueueSummary(
                Integer.parseInt(head.group(1)),
                Integer.parseInt(head.group(2)),
                head.group(3) == null ? 0 : Integer.parseInt(head.group(3)),
                Integer.parseInt(head.group(4)),
                running,
                next,
                blocked));
    }
}
