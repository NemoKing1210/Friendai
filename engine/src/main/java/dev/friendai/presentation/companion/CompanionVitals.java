package dev.friendai.presentation.companion;

/**
 * The companion's body as the menu draws it. Labels are lower-case setting/action labels;
 * everything is zero or empty when there is no companion. {@code xpProgress} runs from 0 to 1 within the level.
 */
public record CompanionVitals(
        float health,
        float maxHealth,
        int food,
        int inventoryUsed,
        int inventorySlots,
        String protection,
        String style,
        String action,
        String reason,
        String paceMode,
        int xpLevel,
        float xpProgress) {
    public static final CompanionVitals NONE = new CompanionVitals(0, 0, 0, 0, 0, "", "", "none", "none", "", 0, 0f);
}
