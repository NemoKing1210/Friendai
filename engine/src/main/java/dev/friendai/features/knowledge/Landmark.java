package dev.friendai.features.knowledge;

import java.util.Locale;

/** One useful block near the companion, with no Minecraft types. Text is English, LLM-facing. */
public record Landmark(String kind, String block, String position, double distance, String direction) {
    public String toPromptText() {
        return block + " at " + position + ", " + String.format(Locale.ROOT, "%.1f", distance) + " blocks " + direction;
    }
}
