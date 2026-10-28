package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.text.UserMessage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryMenuActionsTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000e0");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID X = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final MemoryLimits L = MemoryLimits.DEFAULTS;

    private final MemoryBook book = new MemoryBook();

    private MemoryMenuActions actions(boolean enabled) {
        return new MemoryMenuActions(book, O, "Steve", enabled, L, 5, this::online);
    }

    private MemoryMenuActions actions(boolean enabled, MemoryLimits limits) {
        return new MemoryMenuActions(book, O, "Steve", enabled, limits, 5, this::online);
    }

    private Optional<PlayerRef> online(UUID uuid) {
        return uuid.equals(B) ? Optional.of(new PlayerRef(B, "Bob")) : Optional.empty();
    }

    private static String body(String category, String text) {
        return "{\"category\":\"" + category + "\",\"text\":\"" + text + "\"}";
    }

    @Test
    void personaSavesCleanedText() {
        var s = actions(true).persona("  Calm  guide ");
        assertTrue(s.ok());
        assertEquals("friendai.menu.description.saved", s.message().key());
        assertEquals(Optional.of("Calm guide"), book.persona());
    }

    @Test
    void personaWorksWhenMemoryIsDisabled() {
        assertTrue(actions(false).persona("Calm guide").ok());
        assertEquals(Optional.of("Calm guide"), book.persona());
    }

    @Test
    void blankPersonaClears() {
        book.setPersona("Calm guide");
        var s = actions(true).persona("   ");
        assertTrue(s.ok());
        assertEquals("friendai.menu.description.cleared", s.message().key());
        assertTrue(book.persona().isEmpty());
    }

    @Test
    void badPersonaIsRefusedAndKeepsTheOldOne() {
        book.setPersona("Calm guide");
        for (String bad : new String[] {"§x", "y".repeat(PersonaText.MAX_LENGTH + 1)}) {
            var s = actions(true).persona(bad);
            assertEquals(false, s.ok());
            assertEquals("friendai.menu.description.invalid", s.message().key());
        }
        assertEquals(Optional.of("Calm guide"), book.persona());
        assertTrue(actions(true).persona("Line one\nline two").ok());
        assertEquals(Optional.of("Line one line two"), book.persona());
    }

    @Test
    void addToWorld() {
        var s = actions(true).add("world", body("place", "Base at 1 2 3"));
        assertTrue(s.ok());
        assertEquals(UserMessage.of("friendai.menu.memory.status.added", 1), s.message());
        assertEquals(O, book.fact(1).orElseThrow().authorUuid());
    }

    @Test
    void addToOnlinePlayerCreatesRecord() {
        assertTrue(actions(true).add(B.toString(), body("NOTE", "hi")).ok());
        assertEquals("Bob", book.player(B).orElseThrow().lastName());
    }

    @Test
    void unknownGroupCreatesNothing() {
        var s = actions(true).add(X.toString(), body("NOTE", "hi"));
        assertEquals("friendai.menu.memory.error.unknown_player", s.message().key());
        assertTrue(book.player(X).isEmpty());
        assertTrue(book.allFacts().isEmpty());
    }

    @Test
    void badJsonAndBadIdAreBadRequest() {
        assertEquals(
                "friendai.menu.memory.error.bad_request",
                actions(true).add("world", "{oops").message().key());
        assertEquals(
                "friendai.menu.memory.error.bad_request",
                actions(true).add("world", body("WHAT", "x")).message().key());
        assertEquals(
                "friendai.menu.memory.error.bad_request",
                actions(true).forget("-3").message().key());
        assertEquals(
                "friendai.menu.memory.error.bad_request",
                actions(true).forget("x").message().key());
    }

    @Test
    void oversizedTextIsTooLong() {
        var s = actions(true).add("world", body("NOTE", "y".repeat(2000)));
        assertEquals(UserMessage.of("friendai.menu.memory.error.too_long", 2000, 160), s.message());
        assertTrue(book.allFacts().isEmpty());
    }

    @Test
    void editMakesOwnerTheAuthor() {
        book.ensurePlayer(B, "Bob");
        int id = book.addFact(Subject.player(B), Category.NOTE, "cats", B, "Bob", 0, L)
                .id();
        assertTrue(actions(true)
                .edit(String.valueOf(id), body("PREFERENCE", "Likes cats"))
                .ok());
        assertEquals(O, book.fact(id).orElseThrow().authorUuid());
    }

    @Test
    void editMissingFactIsNoFact() {
        assertEquals(
                UserMessage.of("friendai.menu.memory.error.no_fact", 4),
                actions(true).edit("4", body("NOTE", "x")).message());
    }

    @Test
    void fullWorldUsesItsOwnKey() {
        MemoryMenuActions a = actions(true, new MemoryLimits(160, 20, 1));
        assertTrue(a.add("world", body("NOTE", "one")).ok());
        assertEquals(
                UserMessage.of("friendai.menu.memory.error.full_world", 1),
                a.add("world", body("NOTE", "two")).message());
    }

    @Test
    void fullPlayerUsesTheName() {
        MemoryMenuActions a = actions(true, new MemoryLimits(160, 1, 30));
        book.ensurePlayer(B, "Bob");
        assertTrue(a.add(B.toString(), body("NOTE", "one")).ok());
        assertEquals(
                UserMessage.of("friendai.menu.memory.error.full", "Bob", 1),
                a.add(B.toString(), body("NOTE", "two")).message());
    }

    @Test
    void clearCountsFacts() {
        book.addFact(Subject.WORLD, Category.NOTE, "a", O, "Steve", 0, L);
        book.addFact(Subject.WORLD, Category.NOTE, "b", O, "Steve", 0, L);
        var s = actions(true).clear("world");
        assertTrue(s.ok());
        assertEquals(UserMessage.of("friendai.menu.memory.status.cleared", 2), s.message());
    }

    @Test
    void forgetAndDuplicateAreReported() {
        int first = book.addFact(Subject.WORLD, Category.NOTE, "a", O, "Steve", 0, L)
                .id();
        int second = book.addFact(Subject.WORLD, Category.NOTE, "b", O, "Steve", 0, L)
                .id();
        assertEquals(
                UserMessage.of("friendai.menu.memory.error.duplicate", first),
                actions(true).edit(String.valueOf(second), body("NOTE", "a")).message());
        assertEquals(
                UserMessage.of("friendai.menu.memory.status.forgotten", first),
                actions(true).forget(String.valueOf(first)).message());
        assertEquals(
                UserMessage.of("friendai.menu.memory.error.no_fact", first),
                actions(true).forget(String.valueOf(first)).message());
    }

    @Test
    void disabledBlocksFactsButNotTrust() {
        assertEquals(
                "friendai.menu.memory.error.disabled",
                actions(false).add("world", body("NOTE", "x")).message().key());
        assertEquals(
                "friendai.menu.memory.error.disabled",
                actions(false).edit("1", body("NOTE", "x")).message().key());
        assertEquals(
                "friendai.menu.memory.error.disabled",
                actions(false).forget("1").message().key());
        assertEquals(
                "friendai.menu.memory.error.disabled",
                actions(false).clear("world").message().key());
        assertTrue(actions(false).trust(B.toString(), "true").ok());
        assertTrue(book.isTrusted(B));
    }

    @Test
    void selfTrustAndUnknownTrust() {
        assertEquals(
                "friendai.menu.memory.error.self_trust",
                actions(true).trust(O.toString(), "true").message().key());
        assertEquals(
                "friendai.menu.memory.error.unknown_player",
                actions(true).trust(X.toString(), "true").message().key());
        assertEquals(
                "friendai.menu.memory.error.bad_request",
                actions(true).trust(B.toString(), "yes").message().key());
    }

    @Test
    void trustReportsTheNameBothWays() {
        assertEquals(
                UserMessage.of("friendai.menu.memory.status.trusted", "Bob"),
                actions(true).trust(B.toString(), "true").message());
        assertTrue(book.isTrusted(B));
        assertEquals(
                UserMessage.of("friendai.menu.memory.status.untrusted", "Bob"),
                actions(true).trust(B.toString(), "false").message());
        assertTrue(!book.isTrusted(B));
    }

    @Test
    void trustingAnOfflineRecordUsesItsName() {
        book.ensurePlayer(X, "Gus");
        assertEquals(
                UserMessage.of("friendai.menu.memory.status.trusted", "Gus"),
                actions(true).trust(X.toString(), "true").message());
        assertTrue(book.isTrusted(X));
    }

    @Test
    void ownerGroupNeverNeedsAnOnlineLookup() {
        assertTrue(actions(true)
                .add(O.toString(), body("PROFILE", "Steve likes oak"))
                .ok());
        assertEquals("Steve", book.fact(1).orElseThrow().authorName());
    }
}
