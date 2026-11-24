package dev.friendai.agent.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.TaskWorkResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A receipt proves its own goal or plan, stays bounded in age, and never crosses into unrelated work. */
class VerifiedEvidenceTest {
    private static final long NOW = 100_000L;

    private static VerifiedEvidence receipt(String text, long goalId, long planId, long age) {
        return new VerifiedEvidence(VerifiedEvidence.Kind.DELIVERED, text, 4, 4, goalId, planId, NOW - age);
    }

    private static VerifiedEvidence stock(String itemId, int verified, int requested, long goalId, long planId) {
        return new VerifiedEvidence(VerifiedEvidence.Kind.CARRIED, itemId, verified, requested, goalId, planId, NOW);
    }

    @Test
    void aReceiptIsOfferedToItsGoalAndItsOwnPlanOnly() {
        List<VerifiedEvidence> receipts =
                List.of(receipt("minecraft:stick", 7L, 41L, 0L), receipt("minecraft:stone_pickaxe", 9L, 55L, 0L));

        assertEquals(
                List.of("handed_over 4/4 minecraft:stick"),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW),
                "a new plan for the same goal may use what that goal already verified");
        assertEquals(
                List.of("handed_over 4/4 minecraft:stick"),
                VerifiedEvidence.forObjective(receipts, 0L, 41L, NOW),
                "a repair of the same plan keeps its own receipts");
        assertEquals(
                List.of(),
                VerifiedEvidence.forObjective(receipts, 8L, 0L, NOW),
                "another goal's delivery is not evidence for this one");
        assertEquals(
                List.of(),
                VerifiedEvidence.forObjective(receipts, 0L, 42L, NOW),
                "another plan's receipt is not evidence for this one");
    }

    @Test
    void anObjectiveWithNoGoalAndNoPlanInheritsNothing() {
        List<VerifiedEvidence> receipts = List.of(receipt("minecraft:stone_pickaxe", 9L, 55L, 0L));
        assertEquals(List.of(), VerifiedEvidence.forObjective(receipts, 0L, 0L, NOW));
    }

    @Test
    void anUnownedReceiptProvesNothing() {
        List<VerifiedEvidence> receipts = List.of(receipt("minecraft:stone", 0L, 0L, 0L));
        assertEquals(List.of(), VerifiedEvidence.forObjective(receipts, 0L, 0L, NOW));
        assertEquals(List.of(), VerifiedEvidence.forObjective(receipts, 3L, 4L, NOW));
    }

    @Test
    void aReceiptOlderThanTheAgeBoundIsDropped() {
        List<VerifiedEvidence> fresh = List.of(receipt("minecraft:stick", 7L, 41L, 0L));
        List<VerifiedEvidence> stale = List.of(receipt("minecraft:stick", 7L, 41L, VerifiedEvidence.MAX_AGE_TICKS + 1));

        assertEquals(1, VerifiedEvidence.forObjective(fresh, 7L, 0L, NOW).size());
        assertTrue(VerifiedEvidence.forObjective(stale, 7L, 0L, NOW).isEmpty());
    }

    @Test
    void theOfferedListIsBoundedAndKeepsNewestFirst() {
        List<VerifiedEvidence> receipts = new ArrayList<>();
        for (int i = 0; i < VerifiedEvidence.MAX_ITEMS + 4; i++) {
            receipts.add(
                    new VerifiedEvidence(VerifiedEvidence.Kind.DELIVERED, "minecraft:stick", 1, 1, 7L, 41L, NOW - i));
        }
        List<String> offered = VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW);
        assertEquals(VerifiedEvidence.MAX_ITEMS, offered.size());
        assertEquals("handed_over 1/1 minecraft:stick", offered.get(0));
    }

    @Test
    void aStockReceiptIsDroppedOnceTheItemsAreGone() {
        List<VerifiedEvidence> receipts = List.of(stock("minecraft:stone_pickaxe", 4, 4, 7L, 0L));

        assertEquals(
                List.of(),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW, itemId -> 0),
                "the order handed four tools away, so its own receipt can no longer claim stock");
        assertEquals(
                List.of("carried 4/4 minecraft:stone_pickaxe"),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW, itemId -> 4));
    }

    @Test
    void aPartlyUsedStockReceiptIsOfferedWithWhatRemains() {
        List<VerifiedEvidence> receipts = List.of(stock("minecraft:oak_log", 8, 8, 7L, 0L));
        assertEquals(
                List.of("carried 3/8 minecraft:oak_log"),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW, itemId -> 3),
                "a plan must arrange the rest, not repeat the whole amount");
        assertEquals(
                List.of("carried 8/8 minecraft:oak_log"),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW, itemId -> 20),
                "a live count above the verified amount credits only what was proven");
    }

    @Test
    void aDeliveryNeedsNoLiveCountButOtherKindsDo() {
        List<VerifiedEvidence> receipts = List.of(receipt("minecraft:stick", 7L, 0L, 0L));
        assertEquals(
                List.of("handed_over 4/4 minecraft:stick"),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW),
                "a delivery is history, so it survives the companion inventory changing");

        assertTrue(
                VerifiedEvidence.forObjective(
                                List.of(stock("minecraft:stick", 4, 4, 7L, 0L)),
                                7L,
                                0L,
                                NOW,
                                VerifiedEvidence.LiveStock.NONE)
                        .isEmpty(),
                "with no live view a stock receipt is unproven and dropped");
    }

    @Test
    void receiptsAreBuiltFromTheVerifiedWorkLines() {
        TaskWorkResult result = new TaskWorkResult(
                List.of(
                        new TaskWorkResult.Line("minecraft:stone_pickaxe", 4, 4, TaskWorkResult.Evidence.HANDED_OVER),
                        new TaskWorkResult.Line("minecraft:oak_log", 8, 2, TaskWorkResult.Evidence.CARRIED),
                        new TaskWorkResult.Line("minecraft:coal", 4, 0, TaskWorkResult.Evidence.PICKED_UP)),
                "done",
                "",
                "return");
        List<VerifiedEvidence> receipts = VerifiedEvidence.fromWorkResult(result, 7L, 41L, NOW);

        assertEquals(2, receipts.size(), "only lines with a verified count become receipts");
        assertEquals(
                List.of("handed_over 4/4 minecraft:stone_pickaxe", "carried 2/8 minecraft:oak_log"),
                VerifiedEvidence.forObjective(receipts, 7L, 0L, NOW, itemId -> 2));
        assertTrue(
                VerifiedEvidence.forObjective(receipts, 0L, 41L, NOW, itemId -> 2)
                                .size()
                        == 2,
                "a repair of the same plan sees the same receipts");
    }
}
