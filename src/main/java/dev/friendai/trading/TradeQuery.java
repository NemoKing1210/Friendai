package dev.friendai.trading;

import com.google.gson.JsonParser;

/** BUY inspects results; SELL inspects input predicates. Requirements select exact component values. */
public record TradeQuery(Direction direction, String itemId, String requiredComponentsJson, int limit) {
    public static final int MAX_RESULTS = 40;
    public enum Direction { BUY, SELL }

    public TradeQuery {
        if (direction == null || limit < 1 || limit > MAX_RESULTS) {
            throw new IllegalArgumentException("invalid trade query");
        }
        TradeItem.requireId(itemId);
        if (requiredComponentsJson == null) throw new IllegalArgumentException("missing component requirements");
        requiredComponentsJson = TradeItem.canonicalJson(requiredComponentsJson.isBlank() ? "{}" : requiredComponentsJson);
    }

    public boolean matches(TradeOffer offer) {
        if (direction == Direction.BUY) {
            return itemId.equals(offer.output().itemId()) && componentsMatch(offer.output().componentsJson());
        }
        return offer.effectiveInputs().stream()
                .anyMatch(input -> itemId.equals(input.itemId()) && componentsMatch(input.predicateJson()));
    }

    private boolean componentsMatch(String actual) {
        var requirements = JsonParser.parseString(requiredComponentsJson).getAsJsonObject();
        var components = JsonParser.parseString(actual).getAsJsonObject();
        return requirements.entrySet().stream().allMatch(entry -> components.has(entry.getKey())
                && entry.getValue().toString().equals(components.get(entry.getKey()).toString()));
    }

    /** Historical matches remain visible. No freshness marker authorizes a transaction. */
    public record Match(TraderRecord trader, TradeOffer offer, boolean quoteStale,
                        boolean positionStale, boolean ambiguous) {
        public boolean needsInspection() {
            return quoteStale || positionStale || ambiguous || offer.exhausted();
        }
    }
}
