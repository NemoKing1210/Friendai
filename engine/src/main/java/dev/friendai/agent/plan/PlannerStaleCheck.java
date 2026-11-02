package dev.friendai.agent.plan;

/** Pure stale-result rule for planner patches: a blank expectation is a legacy proposal and is not checked. */
public final class PlannerStaleCheck {
    private PlannerStaleCheck() {}

    public static boolean stale(String expectedFingerprint, String currentFingerprint) {
        return expectedFingerprint != null
                && !expectedFingerprint.isBlank()
                && !expectedFingerprint.equals(currentFingerprint);
    }
}
