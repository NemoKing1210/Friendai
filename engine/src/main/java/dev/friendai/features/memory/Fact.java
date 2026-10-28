package dev.friendai.features.memory;

import java.util.UUID;

/** One remembered fact. The id is stable and never reused, so the model can refer to it in {@code forget}. */
public record Fact(
        int id, Subject subject, Category category, String text, UUID authorUuid, String authorName, long createdAt) {}
