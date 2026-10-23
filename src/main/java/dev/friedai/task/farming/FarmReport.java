package dev.friedai.task.farming;

import java.util.Map;

/** What one farm pass did, as an LLM-facing line. */
public record FarmReport(Map<String, Integer> harvested, int replanted, int planted, int tilled, int noSeeds, boolean noHoe) {
    public String text(int radius) {
        StringBuilder text = new StringBuilder();
        boolean any = false;
        if (!harvested.isEmpty()) {
            text.append("harvested ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : harvested.entrySet()) {
                if (entry.getValue() <= 0) {
                    continue;
                }
                if (!first) {
                    text.append(", ");
                }
                text.append(entry.getValue()).append(' ').append(entry.getKey());
                first = false;
            }
            any = !first;
        }
        if (replanted > 0) {
            append(text, any);
            text.append("replanted ").append(replanted);
            any = true;
        }
        if (planted > 0) {
            append(text, any);
            text.append("planted ").append(planted).append(" new");
            any = true;
        }
        if (tilled > 0) {
            append(text, any);
            text.append("tilled ").append(tilled);
            any = true;
        }
        if (!any) {
            text.append("nothing to harvest or plant within ").append(radius).append(" blocks");
        }
        if (noSeeds > 0) {
            text.append("; no seeds for ").append(noSeeds).append(" empty farmland");
        }
        if (noHoe) {
            text.append("; no hoe to till with");
        }
        return text.toString();
    }

    private static void append(StringBuilder text, boolean any) {
        if (any) {
            text.append("; ");
        }
    }
}
