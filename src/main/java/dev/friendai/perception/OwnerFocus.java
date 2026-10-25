package dev.friendai.perception;

import dev.friendai.survival.HungerModel;
import java.util.Locale;

/**
 * Where the speaking player is and what they see, with no Minecraft types. Text is English, LLM-facing.
 * Only a {@link Visibility#VISIBLE} focus carries position, standing, looking, holding, relative, facing,
 * health and body state; the others leave them null or zero and print none of them.
 */
public record OwnerFocus(
        String label,
        Visibility visibility,
        double distance,
        String position,
        String standingOn,
        String lookingAt,
        String holding,
        String relative,
        String facing,
        float health,
        float maxHealth,
        int food,
        int armor,
        String bodyState) {

    public enum Visibility { VISIBLE, OUT_OF_SIGHT, OTHER_DIMENSION, OFFLINE }

    public static OwnerFocus visible(
            String label,
            double distance,
            String position,
            String standingOn,
            String lookingAt,
            String holding,
            String relative,
            String facing,
            float health,
            float maxHealth,
            int food,
            int armor,
            String bodyState) {
        return new OwnerFocus(label, Visibility.VISIBLE, distance, position, standingOn, lookingAt, holding,
                relative, facing, health, maxHealth, food, armor, bodyState);
    }

    public static OwnerFocus unavailable(String label, Visibility visibility, double distance) {
        return new OwnerFocus(label, visibility, distance, null, null, null, null, null, null, 0f, 0f, 0, 0, null);
    }

    public String toPromptText() {
        return switch (visibility) {
            case OFFLINE -> label + ": offline";
            case OTHER_DIMENSION -> label + ": in another dimension";
            case OUT_OF_SIGHT -> label + ": " + blocksAway()
                    + ", out of sight (I can't tell where they stand or look)";
            case VISIBLE -> label + ": " + blocksAway() + ", in view"
                    + (relative == null ? "" : " (" + relative + " of you)")
                    + (facing == null ? "" : ", " + facing)
                    + "\n  at: " + position
                    + "\n  standing on: " + standingOn
                    + "\n  looking at: " + lookingAt
                    + "\n  holding: " + holding
                    + (bodyState == null ? "" : "\n  health: " + PerceptionSnapshot.number(health) + "/"
                            + PerceptionSnapshot.number(maxHealth)
                            + ", food " + food + "/" + HungerModel.MAX_FOOD + ", armor " + armor
                            + "\n  body: " + bodyState);
        };
    }

    private String blocksAway() {
        return String.format(Locale.ROOT, "%.1f blocks away", distance);
    }
}
