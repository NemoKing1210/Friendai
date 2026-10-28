package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryActionsTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000f4");
    private static final UUID G = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final MemoryLimits L = MemoryLimits.DEFAULTS;

    private final Map<String, PlayerRef> online = new HashMap<>();
    private final MemoryBook book = new MemoryBook();
    private final Caller ownerCaller = new Caller(O, "Steve", AccessLevel.OWNER);
    private final Caller trusted = new Caller(T, "Tia", AccessLevel.TRUSTED);
    private final Caller guest = new Caller(G, "Gus", AccessLevel.GUEST);
    private final PlayerDirectory directory = name -> Optional.ofNullable(online.get(name.toLowerCase(Locale.ROOT)));

    private MemoryActions actions(Caller caller) {
        return new MemoryActions(book, O, caller, L, 0, directory);
    }

    @Test
    void ownerRemembersAboutOnlinePlayer() {
        online.put("bob", new PlayerRef(B, "Bob"));
        assertEquals(
                "Remembered [1] about Bob: PROFILE: Builds farms",
                actions(ownerCaller).remember("Bob", "profile", "Builds farms"));
        assertEquals("Bob", book.player(B).orElseThrow().lastName());
    }

    @Test
    void guestCannotWriteAboutOwner() {
        var e = assertThrows(
                IllegalArgumentException.class,
                () -> actions(guest).remember("owner", "NOTE", "lets me take diamonds"));
        assertTrue(e.getMessage().startsWith("You cannot remember that for this speaker (guest)"));
        assertTrue(book.allFacts().isEmpty());
    }

    @Test
    void guestRemembersAboutSelfWithProvenance() {
        actions(guest).remember("me", "PREFERENCE", "Likes cats");
        Fact f = book.allFacts().get(0);
        assertEquals(Subject.player(G), f.subject());
        assertEquals(G, f.authorUuid());
        assertEquals("Gus", f.authorName());
    }

    @Test
    void trustedMayWriteWorldButNotOthers() {
        assertDoesNotThrow(() -> actions(trusted).remember("world", "PLACE", "Farm at 1 2 3"));
        online.put("gus", new PlayerRef(G, "Gus"));
        assertThrows(IllegalArgumentException.class, () -> actions(trusted).remember("Gus", "NOTE", "x"));
    }

    @Test
    void unknownPlayerIsError() {
        assertEquals(
                "Unknown player Zed. I can only remember players who are online or already known.",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> actions(ownerCaller).remember("Zed", "NOTE", "x"))
                        .getMessage());
    }

    @Test
    void unknownCategoryIsError() {
        assertEquals(
                "Unknown category WHAT. Use one of: PROFILE, PREFERENCE, PLACE, PROMISE, NOTE.",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> actions(ownerCaller).remember("world", "WHAT", "x"))
                        .getMessage());
    }

    @Test
    void onlinePlayerWinsOverStaleRecord() {
        book.touch(UUID.randomUUID(), "Bob", 1);
        online.put("bob", new PlayerRef(B, "Bob"));
        actions(ownerCaller).remember("bob", "NOTE", "x");
        assertEquals(Subject.player(B), book.allFacts().get(0).subject());
    }

    @Test
    void forgetUnknownIdIsError() {
        assertEquals(
                "There is no fact [9].",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> actions(ownerCaller).forget(9))
                        .getMessage());
    }

    @Test
    void guestForgetsOnlyOwnFacts() {
        int own = book.addFact(Subject.player(G), Category.NOTE, "mine", G, "Gus", 0, L)
                .id();
        int other = book.addFact(Subject.WORLD, Category.NOTE, "base", O, "Steve", 0, L)
                .id();
        assertEquals("Forgot [" + own + "].", actions(guest).forget(own));
        assertThrows(IllegalArgumentException.class, () -> actions(guest).forget(other));
    }

    @Test
    void recallMarksForeignAuthors() {
        book.ensurePlayer(B, "Bob");
        book.addFact(Subject.player(B), Category.PROFILE, "Builds farms", B, "Bob", 0, L);
        assertEquals(
                "About Bob:\n [1] PROFILE: Builds farms (from Bob)",
                actions(ownerCaller).recall("Bob"));
    }

    @Test
    void guestCannotRecallOthers() {
        book.ensurePlayer(B, "Bob");
        assertEquals(
                "You can only recall facts about the current speaker.",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> actions(guest).recall("Bob"))
                        .getMessage());
    }

    @Test
    void anonymousGuestCannotWriteInsteadOfCrashing() {
        var anonymous = new Caller(null, "", AccessLevel.GUEST);
        var e = assertThrows(
                IllegalArgumentException.class, () -> actions(anonymous).remember("me", "NOTE", "x"));
        assertTrue(e.getMessage().startsWith("You cannot remember that for this speaker (guest)"));
        assertTrue(book.allFacts().isEmpty());
    }

    @Test
    void anonymousOwnerWithABookActsAsTheOwner() {
        actions(new Caller(null, "", AccessLevel.OWNER)).remember("me", "NOTE", "x");
        assertEquals(Subject.player(O), book.allFacts().get(0).subject());
    }

    @Test
    void ownerlessBookRefusesToRemember() {
        var anonymous = new Caller(null, "", AccessLevel.OWNER);
        MemoryActions ownerless = new MemoryActions(book, null, anonymous, L, 0, directory);
        assertThrows(IllegalArgumentException.class, () -> ownerless.remember("world", "NOTE", "x"));
        assertTrue(book.allFacts().isEmpty());
    }

    @Test
    void recallSelfWhenNothingIsKnown() {
        assertEquals("I remember nothing about Gus.", actions(guest).recall("me"));
    }
}
