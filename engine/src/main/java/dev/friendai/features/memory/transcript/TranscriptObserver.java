package dev.friendai.features.memory.transcript;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.BrainObserver;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Writes both sides of a conversation into the owner's transcript: the player's message when the brain receives
 * it, and the companion's line only when it is actually spoken. Redaction happens before the text reaches the
 * book, and a failure here must never interrupt the brain.
 */
public final class TranscriptObserver implements BrainObserver {
    private final TranscriptBook book;
    private final UUID owner;
    private final LongSupplier clock;
    private final UnaryOperator<String> redactor;
    private final Function<UUID, String> nameOf;

    public TranscriptObserver(
            TranscriptBook book,
            UUID owner,
            LongSupplier clock,
            UnaryOperator<String> redactor,
            Function<UUID, String> nameOf) {
        this.book = book;
        this.owner = owner;
        this.clock = clock;
        this.redactor = redactor == null ? text -> text : redactor;
        this.nameOf = nameOf == null ? uuid -> "" : nameOf;
    }

    @Override
    public void onEvent(BrainEvent event) {
        if (!(event instanceof BrainEvent.PlayerSaid said)) {
            return;
        }
        record(said.playerUuid(), said.playerName(), Speaker.PLAYER, said.text());
    }

    @Override
    public void onReply(Caller caller, String text) {
        if (caller == null || caller.uuid() == null || text == null || text.isBlank()) {
            return;
        }
        if (text.strip().equals("[silent]")) {
            return;
        }
        String name = nameOf.apply(caller.uuid());
        if (name == null || name.isBlank()) {
            name = caller.name();
        }
        record(caller.uuid(), name, Speaker.COMPANION, text);
    }

    private void record(UUID player, String name, Speaker speaker, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        try {
            String clean = redactor.apply(text);
            book.add(player, name, speaker, clean, clock.getAsLong());
        } catch (RuntimeException ignored) {
            // Observers must never break a turn.
        }
    }

    /** The owner this transcript belongs to; kept for diagnostics and tests. */
    public UUID owner() {
        return owner;
    }
}
