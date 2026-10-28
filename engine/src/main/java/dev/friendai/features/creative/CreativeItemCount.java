package dev.friendai.features.creative;

import java.math.BigDecimal;

/** Exact, bounded arithmetic for creative item requests and inventory capacity. */
public final class CreativeItemCount {
    private CreativeItemCount() {}

    /** Parses a JSON number's original text without passing through a lossy double or int conversion. */
    public static long parsePositiveLong(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("count must be a positive integer");
        }
        final long count;
        try {
            count = new BigDecimal(raw).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("count must be an exact integer representable as a long", e);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than zero");
        }
        return count;
    }

    /** Capacity of {@code emptySlots} and partially filled compatible slots, saturating rather than overflowing. */
    public static long capacity(long emptySlots, int maxStackSize, long compatibleRoom) {
        if (emptySlots < 0 || maxStackSize < 1 || compatibleRoom < 0) {
            throw new IllegalArgumentException(
                    "inventory capacity inputs must be non-negative and stack size positive");
        }
        long emptyCapacity = emptySlots > Long.MAX_VALUE / maxStackSize ? Long.MAX_VALUE : emptySlots * maxStackSize;
        if (Long.MAX_VALUE - emptyCapacity < compatibleRoom) {
            return Long.MAX_VALUE;
        }
        return emptyCapacity + compatibleRoom;
    }

    public static Delivery plan(long requested, long capacity) {
        if (requested < 1 || capacity < 0) {
            throw new IllegalArgumentException("requested count must be positive and capacity non-negative");
        }
        long delivered = Math.min(requested, capacity);
        return new Delivery(requested, delivered, requested - delivered);
    }

    public record Delivery(long requested, long delivered, long remaining) {
        public Delivery {
            if (requested < 1 || delivered < 0 || remaining < 0 || delivered + remaining != requested) {
                throw new IllegalArgumentException("delivery counts do not match the request");
            }
        }

        public boolean complete() {
            return remaining == 0;
        }
    }
}
