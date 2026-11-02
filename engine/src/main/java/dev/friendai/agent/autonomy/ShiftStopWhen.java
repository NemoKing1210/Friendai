package dev.friendai.agent.autonomy;

/**
 * Why an autonomous shift stopped. The closed set a shift states before it runs and what the audit records when it
 * ends: the work finished on its own, its budget ran out, the owner took the wheel back, or something else claimed
 * the queue first.
 */
public enum ShiftStopWhen {
    OWNER_BACK("the owner came back"),
    OWNER_SPOKE("the owner spoke"),
    BUDGET_SPENT("the shift's budget ran out"),
    WORK_CONFLICTS("the work no longer fitted the queue"),
    OWNER_PAUSED("the owner paused the work"),
    DONE("the work finished");

    private final String phrase;

    ShiftStopWhen(String phrase) {
        this.phrase = phrase;
    }

    /** One short English line for the model-facing state line; never a translation key. */
    public String phrase() {
        return phrase;
    }
}
