package dev.friendai.features.memory.transcript;

import java.util.UUID;

/**
 * One line of a conversation with a player. The id is stable and never reused, so {@code recent_chat} can hand
 * out a continuation id. Text is already redacted when it reaches the book.
 */
public record TranscriptEntry(
        long id, long atMillis, UUID playerUuid, String playerName, Speaker speaker, String text) {}
