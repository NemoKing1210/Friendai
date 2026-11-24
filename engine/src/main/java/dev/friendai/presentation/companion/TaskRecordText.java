package dev.friendai.presentation.companion;

import java.util.Optional;

/**
 * Reads a history row's stored summary. A row settled with measured work ends with the bounded receipt the work result
 * writes ({@code work: …; reason=…; blocker=…; next=…}); the panel shows the actionable fields before the narrative and
 * can still offer the whole stored text. Pure text in, pure data out.
 */
public final class TaskRecordText {
    /** The receipt suffix, never longer in practice than the task work result's own bounds. */
    public record Receipt(String reason, String blocker, String nextAction) {
        public Receipt {
            reason = reason == null ? "" : reason;
            blocker = blocker == null ? "" : blocker;
            nextAction = nextAction == null ? "" : nextAction;
        }

        public boolean actionable() {
            return !blocker.isBlank() || !reason.isBlank() || !nextAction.isBlank();
        }
    }

    private static final String RECEIPT = "work: ";
    private static final String REASON = "reason=";
    private static final String BLOCKER = "blocker=";
    private static final String NEXT = "next=";
    /** A malformed or hand-written field cannot make the panel allocate unbounded text. */
    public static final int MAX_FIELD_CHARS = 200;

    private final String summary;
    private final String plain;

    private TaskRecordText(String summary, String plain) {
        this.summary = summary;
        this.plain = plain;
    }

    /** Splits the stored summary at its last work receipt; a row without one keeps all of its text as plain. */
    public static TaskRecordText of(String summary) {
        String text = summary == null ? "" : summary;
        int start = text.lastIndexOf(RECEIPT);
        return new TaskRecordText(
                text, start < 0 ? text.strip() : text.substring(0, start).strip());
    }

    /** What the task said in words, before the receipt. */
    public String plain() {
        return plain;
    }

    /** The receipt's actionable fields, when the row ends with one that names a next action. */
    public Optional<Receipt> receipt() {
        int start = summary.lastIndexOf(RECEIPT);
        if (start < 0) return Optional.empty();
        String reason = "";
        String blocker = "";
        String next = "";
        for (String segment : summary.substring(start + RECEIPT.length()).split("; ")) {
            String field = segment.strip();
            if (field.startsWith(REASON)) reason = clip(field.substring(REASON.length()));
            else if (field.startsWith(BLOCKER)) blocker = clip(field.substring(BLOCKER.length()));
            else if (field.startsWith(NEXT)) next = clip(field.substring(NEXT.length()));
        }
        if (next.isBlank()) return Optional.empty();
        return Optional.of(new Receipt(reason, blocker, next));
    }

    /** The one line worth showing before anything else: the blocker, else the reason code, else nothing. */
    public String headline() {
        return receipt()
                .map(receipt -> !receipt.blocker().isBlank() ? receipt.blocker() : receipt.reason())
                .orElse("");
    }

    private static String clip(String value) {
        String normalized = value.replaceAll("[\\p{Cc}\\s]+", " ").strip();
        return normalized.length() <= MAX_FIELD_CHARS
                ? normalized
                : normalized.substring(0, MAX_FIELD_CHARS - 3) + "...";
    }
}
