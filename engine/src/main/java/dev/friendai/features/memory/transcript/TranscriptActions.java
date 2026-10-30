package dev.friendai.features.memory.transcript;

import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.text.TextBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The logic behind {@code recent_chat}: one player's conversation window, both sides, bounded and owner-scoped.
 * The tool never guesses a player: a name must appear in the transcript, or be {@code me} or {@code owner}.
 */
public final class TranscriptActions {
    private TranscriptActions() {}

    /** @param minutes 0 means the configured retention window */
    public static String recent(
            TranscriptBook book,
            UUID owner,
            Caller caller,
            String requested,
            int limit,
            int minutes,
            long beforeId,
            long now,
            int maxChars) {
        return recent(book, owner, caller, requested, limit, minutes, beforeId, now, maxChars, book.limits());
    }

    /** @param activeLimits the current config bounds, so a reloaded retention is enforced on read */
    public static String recent(
            TranscriptBook book,
            UUID owner,
            Caller caller,
            String requested,
            int limit,
            int minutes,
            long beforeId,
            long now,
            int maxChars,
            TranscriptLimits activeLimits) {
        if (owner == null) {
            throw new IllegalArgumentException("The companion has no owner to read a conversation for.");
        }
        if (caller == null || caller.uuid() == null) {
            throw new IllegalArgumentException("Only a player may read a conversation.");
        }
        String name = requested == null || requested.isBlank() ? "me" : requested.strip();
        UUID player;
        String label;
        if (name.equalsIgnoreCase("me")) {
            player = caller.uuid();
            label = caller.name();
        } else if (name.equalsIgnoreCase("owner")) {
            player = owner;
            label = nameOf(book, owner);
        } else {
            TranscriptEntry known = newestNamed(book, name);
            if (known == null) {
                throw new IllegalArgumentException("I have no conversation with " + name + ".");
            }
            player = known.playerUuid();
            label = known.playerName();
        }
        if (!TranscriptPolicy.canRead(owner, caller, player)) {
            throw new IllegalArgumentException("You can only read your own conversation.");
        }
        if (label == null || label.isBlank()) {
            label = nameOf(book, player);
        }
        int capped = Math.max(1, Math.min(50, limit));
        long retention = activeLimits.retentionMillis();
        long window = retention > 0 ? Math.max(0, now - retention) : 0;
        if (minutes > 0) {
            window = Math.max(window, now - minutes * 60_000L);
        }
        List<TranscriptEntry> entries = book.query(player, window, beforeId, capped + 1, true);
        boolean more = entries.size() > capped;
        if (more) {
            entries = new ArrayList<>(entries.subList(0, capped));
        }
        if (entries.isEmpty()) {
            return "No conversation with " + label + " in the last "
                    + (minutes > 0 ? minutes + " minutes." : "retention window.");
        }
        List<String> lines = new ArrayList<>();
        List<TranscriptEntry> chronological = new ArrayList<>(entries);
        java.util.Collections.reverse(chronological);
        for (TranscriptEntry entry : chronological) {
            lines.add(TranscriptText.line(entry, player, label, now));
        }
        TextBudget.Fit fit = TextBudget.fit(lines, maxChars);
        StringBuilder text = new StringBuilder("Conversation with ")
                .append(label)
                .append(": ")
                .append(fit.lines().size())
                .append(entries.size() == 1 ? " line" : " lines")
                .append(", ids ")
                .append(entries.get(entries.size() - 1).id())
                .append("..")
                .append(entries.get(0).id())
                .append('.');
        text.append('\n').append(TextBudget.join(fit));
        boolean truncated = more || fit.truncated() || fit.lines().size() < entries.size();
        text.append("\ntruncated: ").append(truncated);
        if (truncated) {
            text.append("\ncontinuation: recent_chat player=\"")
                    .append(label)
                    .append("\" before=")
                    .append(firstId(fit.lines(), entries.get(entries.size() - 1).id()));
        }
        return text.toString();
    }

    private static TranscriptEntry newestNamed(TranscriptBook book, String name) {
        TranscriptEntry newest = null;
        for (TranscriptEntry entry : book.snapshot()) {
            if (entry.playerName().equalsIgnoreCase(name) && (newest == null || entry.id() > newest.id())) {
                newest = entry;
            }
        }
        return newest;
    }

    private static String nameOf(TranscriptBook book, UUID player) {
        TranscriptEntry newest = null;
        for (TranscriptEntry entry : book.snapshot()) {
            if (entry.playerUuid().equals(player) && (newest == null || entry.id() > newest.id())) {
                newest = entry;
            }
        }
        return newest == null ? "that player" : newest.playerName();
    }

    private static long firstId(List<String> lines, long fallback) {
        if (lines.isEmpty()) {
            return fallback;
        }
        String line = lines.get(0);
        int close = line.indexOf(']');
        if (!line.startsWith("[") || close < 2) {
            return fallback;
        }
        try {
            return Long.parseLong(line.substring(1, close));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
