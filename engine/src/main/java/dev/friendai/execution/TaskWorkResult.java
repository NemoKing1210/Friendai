package dev.friendai.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** A bounded, immutable account of requested work and the evidence the task actually verified. */
public record TaskWorkResult(List<Line> lines, String reasonCode, String blocker, String nextAction) {
    public static final int MAX_LINES = 16;
    public static final int MAX_ITEM_ID_CHARS = 128;
    public static final int MAX_BLOCKER_CHARS = 160;
    public static final int MAX_ACTION_CHARS = 160;
    public static final int MAX_COMPACT_CHARS = 2_048;

    private static final Pattern ITEM_ID = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9_.-]{0,31}");

    public TaskWorkResult {
        if (lines == null || lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("work result must contain at most " + MAX_LINES + " lines");
        }
        lines = List.copyOf(lines);
        Set<String> keys = new HashSet<>();
        for (Line line : lines) {
            String key = line.itemId() + "\u0000" + line.evidence();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("work result contains a duplicate item and evidence row");
            }
        }
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("work result reason code is invalid");
        }
        blocker = boundedLine(blocker, MAX_BLOCKER_CHARS);
        nextAction = boundedLine(nextAction, MAX_ACTION_CHARS);
        if (nextAction.isBlank()) {
            throw new IllegalArgumentException("work result needs a next action");
        }
    }

    /** One line for the model and task log; counts from different evidence categories stay distinct. */
    public String compact() {
        StringBuilder text = new StringBuilder("work: ");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) text.append("; ");
            Line line = lines.get(i);
            text.append(line.itemId())
                    .append(' ')
                    .append(line.evidence().label())
                    .append('=')
                    .append(line.verified())
                    .append('/')
                    .append(line.requested())
                    .append(" remaining=")
                    .append(line.remaining());
        }
        if (!reasonCode.isBlank()) text.append("; reason=").append(reasonCode);
        if (!blocker.isBlank()) text.append("; blocker=").append(blocker);
        text.append("; next=").append(nextAction);
        return boundedLine(text.toString(), MAX_COMPACT_CHARS);
    }

    private static String boundedLine(String text, int limit) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("[\\p{Cc}\\s]+", " ").strip();
        int points = normalized.codePointCount(0, normalized.length());
        return points <= limit ? normalized : normalized.substring(0, normalized.offsetByCodePoints(0, limit));
    }

    /** One measured item and evidence category. A handover drop is not a pickup or carried item. */
    public record Line(String itemId, int requested, int verified, Evidence evidence) {
        public Line {
            if (itemId == null
                    || itemId.length() > MAX_ITEM_ID_CHARS
                    || !ITEM_ID.matcher(itemId).matches()
                    || requested <= 0
                    || verified < 0
                    || evidence == null) {
                throw new IllegalArgumentException("work result line is invalid");
            }
        }

        public int remaining() {
            return Math.max(0, requested - verified);
        }
    }

    public enum Evidence {
        CARRIED("carried"),
        HANDED_OVER("handed_over"),
        PICKED_UP("picked_up");

        private final String label;

        Evidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
