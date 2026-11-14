package dev.friendai.features.farming;

import java.util.Map;

/**
 * What one farm pass did, as an LLM-facing line.
 *
 * @param seedShortfall seeds the last plan was missing, by item id
 * @param unsown empty cells the last plan could not sow
 */
public record FarmReport(
        Map<String, Integer> harvested,
        int replanted,
        int planted,
        int tilled,
        int noSeeds,
        boolean noHoe,
        Map<String, Integer> seedShortfall,
        int unsown) {
    public FarmReport(
            Map<String, Integer> harvested, int replanted, int planted, int tilled, int noSeeds, boolean noHoe) {
        this(harvested, replanted, planted, tilled, noSeeds, noHoe, Map.of(), 0);
    }

    /**
     * This pass followed by a later one over the next part of the same field: work adds up, and what is still missing
     * (seeds, a hoe, unsown cells) is what the later pass found, because the earlier gaps were its starting point.
     */
    public FarmReport plus(FarmReport later) {
        Map<String, Integer> crops = new java.util.LinkedHashMap<>(harvested);
        later.harvested().forEach((crop, count) -> crops.merge(crop, count, Integer::sum));
        return new FarmReport(
                Map.copyOf(crops),
                replanted + later.replanted(),
                planted + later.planted(),
                tilled + later.tilled(),
                later.noSeeds(),
                later.noHoe(),
                later.seedShortfall(),
                later.unsown());
    }

    /** Whether the pass changed anything in the field. */
    public boolean didWork() {
        return harvested.values().stream().anyMatch(count -> count > 0) || replanted > 0 || planted > 0 || tilled > 0;
    }

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
        for (Map.Entry<String, Integer> entry : seedShortfall.entrySet()) {
            text.append("; need ").append(entry.getValue()).append(' ').append(entry.getKey());
        }
        return text.toString();
    }

    private static void append(StringBuilder text, boolean any) {
        if (any) {
            text.append("; ");
        }
    }
}
