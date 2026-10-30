package dev.friendai.features.memory.transcript;

import java.util.UUID;

/** Model-facing rendering of transcript lines: ids for continuation, a relative age and who spoke. */
public final class TranscriptText {
    private TranscriptText() {}

    /** One line: {@code [12] 5m ago You: ...} for a companion line, the player's name otherwise. */
    public static String line(TranscriptEntry entry, UUID viewer, String playerLabel, long now) {
        String who;
        if (entry.speaker() == Speaker.COMPANION) {
            who = "You";
        } else if (entry.playerUuid().equals(viewer)) {
            who = playerLabel == null || playerLabel.isBlank() ? entry.playerName() : playerLabel;
        } else {
            who = entry.playerName();
        }
        return "[" + entry.id() + "] " + age(now - entry.atMillis()) + " " + who + ": " + entry.text();
    }

    /** A compact age: now, minutes, hours or days. */
    public static String age(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return "now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }
}
