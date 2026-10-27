package dev.friendai.creative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreativeItemCountTest {
    @Test
    void acceptsExactPositiveLongCountsWithoutFloatingPointConversion() {
        assertEquals(1, CreativeItemCount.parsePositiveLong("1"));
        assertEquals(1, CreativeItemCount.parsePositiveLong("1.0"));
        assertEquals(Long.MAX_VALUE, CreativeItemCount.parsePositiveLong(Long.toString(Long.MAX_VALUE)));
    }

    @Test
    void rejectsMissingZeroNegativeFractionalAndOverflowCounts() {
        for (String raw : new String[] {null, "", "0", "-1", "1.5", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class, () -> CreativeItemCount.parsePositiveLong(raw), raw);
        }
    }

    @Test
    void capacityHandlesStackableAndNonStackableItemsAndSaturates() {
        assertEquals(1728, CreativeItemCount.capacity(27, 64, 0));
        assertEquals(27, CreativeItemCount.capacity(27, 1, 0));
        assertEquals(Long.MAX_VALUE, CreativeItemCount.capacity(Long.MAX_VALUE, 64, 0));
        assertEquals(Long.MAX_VALUE, CreativeItemCount.capacity(0, 64, Long.MAX_VALUE));
    }

    @Test
    void fullAndPartialInventoriesReportExactDeliveredAndRemainingCounts() {
        CreativeItemCount.Delivery full = CreativeItemCount.plan(64, 0);
        assertEquals(0, full.delivered());
        assertEquals(64, full.remaining());
        assertFalse(full.complete());

        CreativeItemCount.Delivery partial = CreativeItemCount.plan(100, 27);
        assertEquals(27, partial.delivered());
        assertEquals(73, partial.remaining());
        assertFalse(partial.complete());
        assertTrue(CreativeItemCount.plan(4, 10).complete());
    }
}
