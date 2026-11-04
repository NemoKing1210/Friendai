package dev.friendai.features.construction.minecraft.task;

import dev.friendai.features.construction.blueprint.PlacementRefusal;
import java.util.List;

/** The sentences a finished or failed build reports: counts, the remedy rule and the recovery suffix. */
final class BuildReport {
    private BuildReport() {}

    /** The success line: what was placed, cleared and built as foundation, with the parts when only some were asked. */
    static String finished(
            String name,
            String coords,
            String facing,
            int placed,
            String selected,
            int cleared,
            int foundation,
            int selfRecoveries) {
        return "built " + name + " at " + coords + " facing " + facing + ": " + placed + " placed" + selected + ", "
                + cleared + " cleared, " + foundation + " foundation" + recoverySuffix(selfRecoveries);
    }

    /**
     * A typed refusal. A site check happens before any work, so the owner gets the bare cause; a placement
     * refusal says what to do next as well, because the build is already half done.
     */
    static String failed(PlacementRefusal refusal, boolean siteFailure, String resumeHint, int selfRecoveries) {
        return refusal.describe()
                + (siteFailure ? "" : "; " + refusal.remedy())
                + resumeHint
                + recoverySuffix(selfRecoveries);
    }

    /** Several site refusals in one line, then the resume hint. */
    static String failed(List<PlacementRefusal> refusals, String resumeHint, int selfRecoveries) {
        StringBuilder text = new StringBuilder();
        for (PlacementRefusal refusal : refusals) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(refusal.describe());
        }
        return text + resumeHint + recoverySuffix(selfRecoveries);
    }

    /** A failure that is not a placement decision: a configuration problem or a cleanup result. */
    static String plain(String text, int selfRecoveries) {
        return text + recoverySuffix(selfRecoveries);
    }

    static String recoverySuffix(int selfRecoveries) {
        return selfRecoveries == 0 ? "" : ", " + selfRecoveries + " self-blocked recoveries";
    }
}
