package dev.friendai.tool;

import java.util.Locale;

/** Whose position a "near" argument means: the speaker's or the companion's. Pure; the world part is in the tools. */
public enum NearTarget {
    /** The player who is talking to the companion. */
    ME,
    /** The companion itself. */
    SELF;

    /** Blank or missing means the speaker; anything but the known words is an error the model can read. */
    public static NearTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ME;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "me", "you", "speaker" -> ME;
            case "self", "companion" -> SELF;
            default -> throw new IllegalArgumentException("near must be \"me\", \"self\" or \"place:<name>\"");
        };
    }
}
