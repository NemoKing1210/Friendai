package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryPolicyTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID G = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000f4");
    private static final UUID X = UUID.fromString("00000000-0000-0000-0000-0000000000f5");

    private final Caller ownerCaller = new Caller(O, "Steve", AccessLevel.OWNER);
    private final Caller trusted = new Caller(T, "Tia", AccessLevel.TRUSTED);
    private final Caller guest = new Caller(G, "Gus", AccessLevel.GUEST);

    private static Fact factAbout(Subject subject, UUID author) {
        return new Fact(1, subject, Category.NOTE, "x", author, "Author", 0);
    }

    @Test
    void ownerWritesEverything() {
        assertTrue(MemoryPolicy.canWrite(O, ownerCaller, Subject.player(O)));
        assertTrue(MemoryPolicy.canWrite(O, ownerCaller, Subject.player(B)));
        assertTrue(MemoryPolicy.canWrite(O, ownerCaller, Subject.WORLD));
    }

    @Test
    void trustedWritesSelfAndWorldOnly() {
        assertTrue(MemoryPolicy.canWrite(O, trusted, Subject.player(T)));
        assertFalse(MemoryPolicy.canWrite(O, trusted, Subject.player(B)));
        assertTrue(MemoryPolicy.canWrite(O, trusted, Subject.WORLD));
    }

    @Test
    void guestWritesOnlySelf() {
        assertTrue(MemoryPolicy.canWrite(O, guest, Subject.player(G)));
        assertFalse(MemoryPolicy.canWrite(O, guest, Subject.player(B)));
        assertFalse(MemoryPolicy.canWrite(O, guest, Subject.WORLD));
    }

    @Test
    void ownerForgetsAnyFact() {
        assertTrue(MemoryPolicy.canForget(O, ownerCaller, factAbout(Subject.player(O), X)));
        assertTrue(MemoryPolicy.canForget(O, ownerCaller, factAbout(Subject.player(B), T)));
        assertTrue(MemoryPolicy.canForget(O, ownerCaller, factAbout(Subject.player(B), X)));
    }

    @Test
    void trustedForgetsOwnFactsAndFactsAboutSelf() {
        assertTrue(MemoryPolicy.canForget(O, trusted, factAbout(Subject.player(T), X)));
        assertTrue(MemoryPolicy.canForget(O, trusted, factAbout(Subject.player(B), T)));
        assertFalse(MemoryPolicy.canForget(O, trusted, factAbout(Subject.player(B), X)));
    }

    @Test
    void guestForgetsOnlyFactsAboutSelf() {
        assertTrue(MemoryPolicy.canForget(O, guest, factAbout(Subject.player(G), X)));
        assertFalse(MemoryPolicy.canForget(O, guest, factAbout(Subject.player(B), G)));
        assertFalse(MemoryPolicy.canForget(O, guest, factAbout(Subject.player(B), X)));
    }

    @Test
    void anonymousCallersAreDeniedInsteadOfMatchingNull() {
        var anonymousGuest = new Caller(null, "", AccessLevel.GUEST);
        var anonymousTrusted = new Caller(null, "", AccessLevel.TRUSTED);
        assertFalse(MemoryPolicy.canWrite(O, anonymousGuest, Subject.player(null)));
        assertFalse(MemoryPolicy.canWrite(O, anonymousTrusted, Subject.player(null)));
        assertFalse(MemoryPolicy.canRecall(O, anonymousGuest, null));
        assertFalse(MemoryPolicy.canRecall(O, anonymousTrusted, null));
    }

    @Test
    void ownerlessBookRefusesEverything() {
        var anonymousOwner = new Caller(null, "", AccessLevel.OWNER);
        assertFalse(MemoryPolicy.canWrite(null, anonymousOwner, Subject.WORLD));
        assertFalse(MemoryPolicy.canRecall(null, anonymousOwner, O));
        assertFalse(MemoryPolicy.canForget(null, anonymousOwner, factAbout(Subject.WORLD, O)));
    }

    @Test
    void recallFollowsTheSameSelfRule() {
        assertTrue(MemoryPolicy.canRecall(O, ownerCaller, O));
        assertTrue(MemoryPolicy.canRecall(O, ownerCaller, B));
        assertTrue(MemoryPolicy.canRecall(O, trusted, T));
        assertFalse(MemoryPolicy.canRecall(O, trusted, B));
        assertTrue(MemoryPolicy.canRecall(O, guest, G));
        assertFalse(MemoryPolicy.canRecall(O, guest, B));
    }
}
