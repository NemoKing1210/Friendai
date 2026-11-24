package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.verify.VerifiedEvidence;
import dev.friendai.features.memory.MemoryBook;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A session offers a receipt only to the goal or plan it proves, and keeps the store bounded. */
class CompanionSessionEvidenceTest {
    private static final long NOW = 5_000L;

    private static VerifiedEvidence delivered(String itemId, long goalId, long planId) {
        return new VerifiedEvidence(VerifiedEvidence.Kind.DELIVERED, itemId, 4, 4, goalId, planId, NOW);
    }

    @Test
    void onlyTheOwningGoalAndPlanSeeAReceipt() {
        CompanionSession session = new CompanionSession(UUID.randomUUID(), new MemoryBook());
        session.noteVerified(delivered("minecraft:stick", 7L, 41L));

        assertEquals(List.of("handed_over 4/4 minecraft:stick"), session.verifiedFacts(7L, 0L, NOW));
        assertEquals(List.of("handed_over 4/4 minecraft:stick"), session.verifiedFacts(0L, 41L, NOW));
        assertEquals(List.of(), session.verifiedFacts(8L, 0L, NOW), "another goal inherits nothing");
        assertEquals(List.of(), session.verifiedFacts(0L, 0L, NOW), "an unowned objective inherits nothing");
        assertEquals(
                List.of(),
                session.verifiedFacts(7L, 0L, NOW + VerifiedEvidence.MAX_AGE_TICKS + 1),
                "a receipt past its age bound is not offered");
    }

    @Test
    void anUnownedReceiptIsNotKept() {
        CompanionSession session = new CompanionSession(UUID.randomUUID(), new MemoryBook());
        session.noteVerified(delivered("minecraft:stone_pickaxe", 7L, 0L));
        for (int i = 0; i < VerifiedEvidence.MAX_ITEMS + 3; i++) {
            session.noteVerified(
                    new VerifiedEvidence(VerifiedEvidence.Kind.CARRIED, "minecraft:stick", 1, 1, 0L, 0L, NOW));
        }

        assertEquals(
                List.of("handed_over 4/4 minecraft:stone_pickaxe"),
                session.verifiedFacts(7L, 0L, NOW),
                "unattributable receipts must not crowd out one that a goal can use");
    }

    @Test
    void theStoreKeepsOnlyTheNewestReceipts() {
        CompanionSession session = new CompanionSession(UUID.randomUUID(), new MemoryBook());
        for (int i = 0; i < VerifiedEvidence.MAX_ITEMS + 3; i++) {
            session.noteVerified(delivered("/minecraft/item_" + i, 7L, 41L));
        }
        List<String> offered = session.verifiedFacts(7L, 0L, NOW);
        assertEquals(VerifiedEvidence.MAX_ITEMS, offered.size());
        assertEquals("handed_over 4/4 /minecraft/item_10", offered.get(0), "newest first");
    }
}
