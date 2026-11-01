package dev.friendai.features.social;

import java.util.List;

/**
 * One short line for the system prompt that says how the companion feels about this owner and why.
 *
 * <p>Built from the mood, the tier and the newest reason only. It carries no number — a score in the prompt invites
 * the model to game it — and invents no feeling: at the base tier with a calm face and no reason it renders nothing.
 */
public final class BondPrompt {
    /** Long enough for one tier, one mood and one reason; short enough to stay cheap in every turn. */
    public static final int MAX_CHARS = 160;

    private BondPrompt() {}

    public static String line(BondLedger.Tier tier, Mood mood, List<String> reasons, boolean ownerCaller) {
        return line(tier, mood, reasons, Traits.NEUTRAL, ownerCaller);
    }

    /** The same line plus the drifted manner, which is only ever a style note and never a feeling. */
    public static String line(
            BondLedger.Tier tier, Mood mood, List<String> reasons, Traits traits, boolean ownerCaller) {
        if (!ownerCaller) {
            return "";
        }
        String tierText = switch (tier == null ? BondLedger.Tier.STRANGER : tier) {
            case STRANGER -> "";
            case ACQUAINTANCE -> "you are getting used to this player";
            case FRIEND -> "you count this player as a friend";
            case CLOSE -> "this player is close to you";
        };
        String moodText = switch (mood == null ? Mood.CALM : mood) {
            case CALM -> "";
            case FOCUSED -> "you are focused";
            case WORRIED -> "you are worried";
            case PLEASED -> "you are pleased";
            case DISPLEASED -> "you are displeased";
            case COMBAT -> "you are in a fight";
        };
        String reason = reasons == null || reasons.isEmpty() ? "" : "remembered: " + reasons.getFirst();
        String drift = traits == null ? "" : traits.toneLine();
        String body = join(tierText, moodText, reason);
        String line = body.isEmpty() ? "" : "How you feel right now: " + body + ".";
        if (!drift.isBlank()) {
            line = line.isBlank() ? drift : line + " " + drift;
        }
        return line.isBlank() ? "" : clip(line, MAX_CHARS + 80);
    }

    private static String join(String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(part);
        }
        return text.toString();
    }

    private static String clip(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).stripTrailing() + ".";
    }
}
