package dev.friendai.features.trading;

/** One positive vanilla input cost, preserving its component predicate as canonical plain JSON. */
public record TradeCost(String itemId, String predicateJson, int count) {
    public TradeCost {
        TradeItem.requireId(itemId);
        TradeItem.requireCount(count);
        predicateJson = TradeItem.canonicalJson(predicateJson);
    }
}
