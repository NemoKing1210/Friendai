package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskRecordTextTest {
    private static final String RECEIPT_ROW =
            "Searching for a village ran out of time. work: minecraft:bread carried=0/3 remaining=3; "
                    + "reason=out_of_time; blocker=no loaded chunks ahead; next=walk closer and search again";

    @Test
    void readsTheReceiptBesideThePlainText() {
        TaskRecordText text = TaskRecordText.of(RECEIPT_ROW);

        assertEquals("Searching for a village ran out of time.", text.plain());
        TaskRecordText.Receipt receipt = text.receipt().orElseThrow();
        assertEquals("out_of_time", receipt.reason());
        assertEquals("no loaded chunks ahead", receipt.blocker());
        assertEquals("walk closer and search again", receipt.nextAction());
        assertTrue(receipt.actionable());
        assertEquals("no loaded chunks ahead", text.headline());
    }

    @Test
    void aRowWithoutAReceiptKeepsAllOfItsText() {
        TaskRecordText text = TaskRecordText.of("  Crafted 4 planks.  ");

        assertEquals("Crafted 4 planks.", text.plain());
        assertTrue(text.receipt().isEmpty());
        assertEquals("", text.headline());
        assertTrue(TaskRecordText.of(null).plain().isEmpty());
    }

    @Test
    void theReasonLeadsWhenThereIsNoBlocker() {
        TaskRecordText text = TaskRecordText.of("Moved what fit. work: minecraft:stone picked_up=3/8 remaining=5; "
                + "reason=inventory_full; next=free a slot");

        assertEquals("inventory_full", text.headline());
        assertEquals("", text.receipt().orElseThrow().blocker());
    }

    @Test
    void anIncompleteReceiptIsNotTreatedAsStructured() {
        TaskRecordText text = TaskRecordText.of("Half written. work: minecraft:stone carried=1/2 remaining=1");

        assertTrue(text.receipt().isEmpty());
        assertEquals("Half written.", text.plain());
        assertFalse(text.headline().contains("work:"));
    }

    @Test
    void aMalformedFieldCannotGrowWithoutBound() {
        String longBlocker = "x".repeat(TaskRecordText.MAX_FIELD_CHARS * 2);
        TaskRecordText text = TaskRecordText.of(
                "Odd row. work: minecraft:dirt carried=1/1 remaining=0; blocker=" + longBlocker + "; next=move");

        TaskRecordText.Receipt receipt = text.receipt().orElseThrow();
        assertEquals(TaskRecordText.MAX_FIELD_CHARS, receipt.blocker().length());
        assertTrue(receipt.blocker().endsWith("..."));
    }
}
