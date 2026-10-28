package dev.friendai.features.trading;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Cumulative barter evidence separating accepted payments, emitted entities and inventory receipts. */
public record BarterProgress(
        UUID orderId, int goldAccepted, Set<UUID> emittedItems, List<TradeItem> collected, State state, String reason) {
    public enum State {
        AUTHORIZED,
        RUNNING,
        GOLD_ACCEPTED,
        OUTPUT_EMITTED,
        COLLECTED,
        PARTIAL,
        LOST,
        UNCERTAIN,
        INTERRUPTED
    }

    public static final int MAX_EMITTED_ITEMS = 1_024;
    public static final int MAX_COLLECTED_STACKS = 2_048;

    public BarterProgress {
        if (orderId == null
                || goldAccepted < 0
                || emittedItems == null
                || emittedItems.size() > MAX_EMITTED_ITEMS
                || emittedItems.stream().anyMatch(id -> id == null)
                || collected == null
                || collected.size() > MAX_COLLECTED_STACKS
                || collected.stream().anyMatch(item -> item == null)
                || state == null) throw new IllegalArgumentException("invalid barter progress");
        emittedItems = Set.copyOf(new HashSet<>(emittedItems));
        collected = List.copyOf(collected);
        reason = TradeItem.requireText(reason == null ? "" : reason, 512);
        long total = collected.stream().mapToLong(TradeItem::count).sum();
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("barter receipt count overflow");
    }

    public boolean terminal() {
        return switch (state) {
            case AUTHORIZED, RUNNING, GOLD_ACCEPTED, OUTPUT_EMITTED -> false;
            case COLLECTED, PARTIAL, LOST, UNCERTAIN, INTERRUPTED -> true;
        };
    }

    public int collectedCount(TradeQuery query) {
        int total = 0;
        for (TradeItem item : collected) {
            if (query.direction() == TradeQuery.Direction.BUY
                    && item.itemId().equals(query.itemId())
                    && componentsMatch(query.requiredComponentsJson(), item.componentsJson())) {
                total = Math.addExact(total, item.count());
            }
        }
        return total;
    }

    private static boolean componentsMatch(String requiredJson, String actualJson) {
        var required = com.google.gson.JsonParser.parseString(requiredJson).getAsJsonObject();
        var actual = com.google.gson.JsonParser.parseString(actualJson).getAsJsonObject();
        return required.entrySet().stream()
                .allMatch(
                        entry -> actual.has(entry.getKey()) && entry.getValue().equals(actual.get(entry.getKey())));
    }
}
