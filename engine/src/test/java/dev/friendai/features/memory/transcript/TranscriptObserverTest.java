package dev.friendai.features.memory.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptObserverTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Test
    void bothSidesAreStoredWithNormalizedText() {
        TranscriptBook book = new TranscriptBook(TranscriptLimits.DEFAULTS);
        TranscriptObserver observer = new TranscriptObserver(book, OWNER, () -> 5_000, text -> text, uuid -> "Owner");
        observer.onEvent(new BrainEvent.PlayerSaid(OWNER, "Alex", "  hi ", BrainEvent.Source.CHAT));
        observer.onReply(new Caller(OWNER, "Alex", AccessLevel.OWNER), "hello there");
        List<TranscriptEntry> entries = book.snapshot();
        assertEquals(2, entries.size());
        assertEquals(Speaker.PLAYER, entries.get(0).speaker());
        assertEquals("hi", entries.get(0).text());
        assertEquals(Speaker.COMPANION, entries.get(1).speaker());
        assertEquals("Owner", entries.get(1).playerName());
        assertEquals("hello there", entries.get(1).text());
    }

    @Test
    void silentAndEventlessLinesAreNotStored() {
        TranscriptBook book = new TranscriptBook(TranscriptLimits.DEFAULTS);
        TranscriptObserver observer = new TranscriptObserver(book, OWNER, () -> 5_000, text -> text, uuid -> "Owner");
        observer.onReply(new Caller(OWNER, "", AccessLevel.OWNER), "[silent]");
        observer.onReply(new Caller(OWNER, "", AccessLevel.OWNER), "  ");
        observer.onEvent(new BrainEvent.WorldNotice("furnace done"));
        assertTrue(book.snapshot().isEmpty());
    }

    @Test
    void redactionHappensBeforeTheBook() {
        TranscriptBook book = new TranscriptBook(TranscriptLimits.DEFAULTS);
        TranscriptObserver observer = new TranscriptObserver(
                book, OWNER, () -> 5_000, text -> text.replace("secret", "[redacted]"), uuid -> "Owner");
        observer.onEvent(new BrainEvent.PlayerSaid(OWNER, "Alex", "my secret", BrainEvent.Source.CHAT));
        assertEquals("[redacted]", book.snapshot().get(0).text().replace("my ", ""));
    }

    @Test
    void aFailingRedactorNeverBreaksTheTurn() {
        TranscriptBook book = new TranscriptBook(TranscriptLimits.DEFAULTS);
        TranscriptObserver observer = new TranscriptObserver(
                book,
                OWNER,
                () -> 5_000,
                text -> {
                    throw new IllegalStateException("boom");
                },
                uuid -> "Owner");
        observer.onEvent(new BrainEvent.PlayerSaid(OWNER, "Alex", "hi", BrainEvent.Source.CHAT));
        assertTrue(book.snapshot().isEmpty());
    }
}
