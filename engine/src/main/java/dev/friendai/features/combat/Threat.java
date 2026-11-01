package dev.friendai.features.combat;

import java.util.Locale;

/**
 * One hostile mob near the companion, with no Minecraft types. {@code target} is one of {@code me},
 * {@code owner}, {@code someone else} or {@code nothing}; text is English, LLM-facing.
 */
public record Threat(
        String type,
        double distance,
        String direction,
        float health,
        float maxHealth,
        String target,
        boolean attackable) {
    public String toPromptText() {
        String line = type + " " + String.format(Locale.ROOT, "%.1f", distance) + " blocks " + direction + ", "
                + Math.round(health) + "/" + Math.round(maxHealth) + " hp, " + targetText();
        return attackable ? line : line + " — danger, do not attack";
    }

    private String targetText() {
        return switch (target) {
            case "me" -> "coming for me";
            case "owner" -> "coming for the owner";
            case "someone else" -> "fighting someone else";
            default -> "not targeting anyone";
        };
    }
}
