package dev.friendai.perception;

import java.util.Locale;

/**
 * Where the speaking player is and what they see, with no Minecraft types. Text is English, LLM-facing.
 * Only a {@link Visibility#VISIBLE} focus carries position, standing, looking and holding; the others leave them null.
 */
public record OwnerFocus(
        String label,
        Visibility visibility,
        double distance,
        String position,
        String standingOn,
        String lookingAt,
        String holding) {

    public enum Visibility { VISIBLE, OUT_OF_SIGHT, OTHER_DIMENSION, OFFLINE }

    public String toPromptText() {
        return switch (visibility) {
            case OFFLINE -> label + ": offline";
            case OTHER_DIMENSION -> label + ": in another dimension";
            case OUT_OF_SIGHT -> label + ": " + blocksAway()
                    + ", out of sight (I can't tell where they stand or look)";
            case VISIBLE -> label + ": " + blocksAway() + ", in view"
                    + "\n  at: " + position
                    + "\n  standing on: " + standingOn
                    + "\n  looking at: " + lookingAt
                    + "\n  holding: " + holding;
        };
    }

    private String blocksAway() {
        return String.format(Locale.ROOT, "%.1f blocks away", distance);
    }
}
