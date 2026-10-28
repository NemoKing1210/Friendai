package dev.friendai.server.agent;

import dev.friendai.foundation.text.LanguageDetector;
import dev.friendai.foundation.text.LanguageMemory;
import dev.friendai.memory.AutoProfile;
import dev.friendai.memory.MemoryBook;
import dev.friendai.memory.MemoryLimits;
import java.util.Objects;
import java.util.UUID;

/** Learns the owner's language and explicitly stated profile details from the owner's own messages. */
public final class OwnerProfileObserver {
    private final UUID owner;
    private final MemoryBook book;
    private final LanguageMemory language;

    public OwnerProfileObserver(UUID owner, MemoryBook book) {
        this.owner = Objects.requireNonNull(owner);
        this.book = Objects.requireNonNull(book);
        this.language = LanguageMemory.startingAt(book.ownerLanguage(owner).orElse(null));
    }

    /** Ensures the owner record and observes one owner's message. */
    public void observe(String playerName, String text, boolean memoryEnabled, MemoryLimits limits, long nowMillis) {
        book.ensurePlayer(owner, playerName);
        language.observe(LanguageDetector.detect(text)).ifPresent(next -> book.setOwnerLanguage(owner, next));
        if (!memoryEnabled) {
            return;
        }
        book.ownerLanguage(owner)
                .ifPresent(current -> AutoProfile.syncLanguage(book, owner, playerName, current, nowMillis, limits));
        AutoProfile.record(book, owner, playerName, text, nowMillis, limits);
    }
}
