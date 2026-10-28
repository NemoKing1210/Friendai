package dev.friendai.features.knowledge;

import java.util.List;

/** Small, uncertainty-aware summaries for resource knowledge shown to the companion. */
public final class ResourceKnowledgeText {
    private static final int DEFAULT_SITES = 3;

    private ResourceKnowledgeText() {}

    public static String summary(List<ResourceQuery.Result> sites, int maxChars) {
        if (sites == null || sites.isEmpty() || maxChars <= 0) return "";
        StringBuilder text = new StringBuilder("Remembered resource evidence (historical, not guaranteed): ");
        int count = 0;
        for (ResourceQuery.Result result : sites) {
            if (count >= DEFAULT_SITES) break;
            ResourceSite site = result.site();
            String entry = (count == 0 ? "" : "; ") + site.family() + " in " + site.dimension()
                    + ", about " + Math.round(result.distanceBlocks()) + " blocks away, observed "
                    + result.ageTicks() + " ticks ago, count " + result.lastObservedBlocks()
                    + (site.approximate() ? " (approximate)" : " (exact positions)");
            if (text.length() + entry.length() > maxChars) break;
            text.append(entry);
            count++;
        }
        if (count == 0) return "";
        String suffix = ". Unknown means unseen, not absent.";
        if (text.length() + suffix.length() <= maxChars) text.append(suffix);
        else if (text.length() < maxChars) text.append('.');
        return text.length() <= maxChars ? text.toString() : text.substring(0, maxChars);
    }
}
