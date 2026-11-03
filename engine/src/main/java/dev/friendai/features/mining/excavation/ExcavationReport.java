package dev.friendai.features.mining.excavation;

import java.util.Map;

/** Model-facing sentences built only from measured counters. */
public final class ExcavationReport {
    private ExcavationReport() {}

    public static String success(String label, ExcavationProgress progress) {
        return "mined " + progress.broken() + " " + label + takenText(progress)
                + (progress.skipped() + progress.refused() == 0
                        ? ""
                        : "; skipped " + (progress.skipped() + progress.refused()) + " that could not be taken");
    }

    public static String partial(String label, ExcavationProgress progress, String reason) {
        return "mined " + progress.broken() + " " + label + takenText(progress) + "; stopped: " + reason;
    }

    public static String notStarted(String label, String reason) {
        return "did not excavate " + label + ": " + reason;
    }

    private static String takenText(ExcavationProgress progress) {
        Map<String, Integer> taken = progress.taken();
        if (taken.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(" and took ");
        int index = 0;
        for (Map.Entry<String, Integer> entry : taken.entrySet()) {
            if (index++ > 0) {
                text.append(", ");
            }
            text.append(entry.getValue()).append(' ').append(entry.getKey());
        }
        return text.toString();
    }
}
