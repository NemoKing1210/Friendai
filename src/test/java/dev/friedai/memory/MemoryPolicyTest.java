package dev.friedai.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.Caller;
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
    void recallFollowsTheSameSelfRule() {
        assertTrue(MemoryPolicy.canRecall(O, ownerCaller, O));
        assertTrue(MemoryPolicy.canRecall(O, ownerCaller, B));
        assertTrue(MemoryPolicy.canRecall(O, trusted, T));
        assertFalse(MemoryPolicy.canRecall(O, trusted, B));
        assertTrue(MemoryPolicy.canRecall(O, guest, G));
        assertFalse(MemoryPolicy.canRecall(O, guest, B));
    }
}
