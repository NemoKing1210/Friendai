package dev.friendai.features.memory.transcript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptActionsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID GUS = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Caller OWNER_CALLER = new Caller(OWNER, "Alex", AccessLevel.OWNER);
    private static final TranscriptLimits LIMITS = new TranscriptLimits(50, 400, 86_400_000, 200_000);

    private static TranscriptBook book() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        book.add(OWNER, "Alex", Speaker.PLAYER, "hello", 1_000);
        book.add(OWNER, "Alex", Speaker.COMPANION, "hi there", 2_000);
        return book;
    }

    @Test
    void showsBothSidesChronologicallyWithIds() {
        String text = TranscriptActions.recent(book(), OWNER, OWNER_CALLER, "me", 10, 0, 0, 3_000, 6_000);
        assertTrue(text.contains("Conversation with Alex"), text);
        assertTrue(text.contains("[1]"), text);
        assertTrue(text.contains("Alex: hello"), text);
        assertTrue(text.contains("[2]"), text);
        assertTrue(text.contains("You: hi there"), text);
        assertTrue(text.contains("truncated: false"), text);
        assertFalse(text.contains("continuation:"), text);
    }

    @Test
    void aTrustedPlayerSeesOnlyTheirOwnConversation() {
        TranscriptBook book = book();
        book.add(GUS, "Gus", Speaker.PLAYER, "my own line", 3_000);
        Caller gus = new Caller(GUS, "Gus", AccessLevel.TRUSTED);
        assertTrue(TranscriptActions.recent(book, OWNER, gus, "me", 10, 0, 0, 4_000, 6_000)
                .contains("my own line"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TranscriptActions.recent(book, OWNER, gus, "Alex", 10, 0, 0, 4_000, 6_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> TranscriptActions.recent(book, OWNER, gus, "owner", 10, 0, 0, 4_000, 6_000));
    }

    @Test
    void aGuestReadsNothing() {
        Caller guest = new Caller(UUID.randomUUID(), "Guest", AccessLevel.GUEST);
        assertThrows(
                IllegalArgumentException.class,
                () -> TranscriptActions.recent(book(), OWNER, guest, "me", 10, 0, 0, 3_000, 6_000));
    }

    @Test
    void anUnknownNameIsNotGuessed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TranscriptActions.recent(book(), OWNER, OWNER_CALLER, "Bob", 10, 0, 0, 3_000, 6_000));
    }

    @Test
    void limitTruncatesAndCarriesABackwardsContinuation() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        for (int i = 1; i <= 5; i++) {
            book.add(OWNER, "Alex", Speaker.PLAYER, "m" + i, i * 1_000L);
        }
        String text = TranscriptActions.recent(book, OWNER, OWNER_CALLER, "me", 2, 0, 0, 6_000, 6_000);
        assertTrue(text.contains("m5") && text.contains("m4"), text);
        assertFalse(text.contains("m3"), text);
        assertTrue(text.contains("truncated: true"), text);
        assertTrue(text.contains("before=4"), text);
    }

    @Test
    void minutesNarrowTheWindow() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        book.add(OWNER, "Alex", Speaker.PLAYER, "old", 0);
        book.add(OWNER, "Alex", Speaker.PLAYER, "new", 7_200_000);
        String text = TranscriptActions.recent(book, OWNER, OWNER_CALLER, "me", 10, 30, 0, 7_260_000, 6_000);
        assertTrue(text.contains("new"), text);
        assertFalse(text.contains("old"), text);
    }

    @Test
    void retentionHidesExpiredLines() {
        TranscriptBook book = new TranscriptBook(new TranscriptLimits(50, 400, 1_000, 200_000));
        book.add(OWNER, "Alex", Speaker.PLAYER, "ancient", 0);
        String text = TranscriptActions.recent(book, OWNER, OWNER_CALLER, "me", 10, 0, 0, 5_000, 6_000);
        assertTrue(text.contains("No conversation with Alex"), text);
    }

    @Test
    void ownerResolvesToTheOwnerConversation() {
        String text = TranscriptActions.recent(book(), OWNER, OWNER_CALLER, "owner", 10, 0, 0, 3_000, 6_000);
        assertTrue(text.contains("Alex: hello"), text);
    }
}
