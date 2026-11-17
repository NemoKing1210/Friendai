package dev.friendai.execution;

/** The terminal outcome of one task activation or queue plan. */
public enum TaskDisposition {
    SUCCESS("SUCCESS", "finished", false),
    FAILED("FAILED", "failed", true),
    TIMED_OUT("TIMED_OUT", "timed out", true),
    CANCELLED("CANCELLED", "cancelled", false);

    private final String historyStatus;
    private final String promptVerb;
    private final boolean failure;

    TaskDisposition(String historyStatus, String promptVerb, boolean failure) {
        this.historyStatus = historyStatus;
        this.promptVerb = promptVerb;
        this.failure = failure;
    }

    public String historyStatus() {
        return historyStatus;
    }

    public String promptVerb() {
        return promptVerb;
    }

    public boolean failure() {
        return failure;
    }

    /** Legacy history constructors used only these known labels; new code carries the enum directly. */
    public static TaskDisposition fromHistoryStatus(String status) {
        if (status == null) return FAILED;
        return switch (status.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS" -> SUCCESS;
            case "TIMED_OUT" -> TIMED_OUT;
            case "CANCELLED" -> CANCELLED;
            case "FAILED" -> FAILED;
            default -> throw new IllegalArgumentException("unknown task history status");
        };
    }
}
