package dev.friendai.trading;

import java.util.Map;
import java.util.TreeMap;

/** Owner-approved input limits for one trading scope. Counts are item-id totals. */
public record TradeBudget(Map<String, Integer> inputCaps, Map<String, Integer> reserves,
                          Map<String, Integer> maxUnitCosts) {
    public static final int MAX_INPUT_KINDS = 256;

    public TradeBudget {
        inputCaps = counts(inputCaps, false, "input cap");
        reserves = counts(reserves, true, "reserve");
        maxUnitCosts = counts(maxUnitCosts, true, "maximum unit cost");
        if (inputCaps.isEmpty()) throw new IllegalArgumentException("trade budget needs at least one input cap");
        if (!inputCaps.keySet().containsAll(reserves.keySet())
                || !inputCaps.keySet().containsAll(maxUnitCosts.keySet())) {
            throw new IllegalArgumentException("trade budget constraints need matching input caps");
        }
    }

    private static Map<String, Integer> counts(Map<String, Integer> values, boolean allowEmpty, String label) {
        if (values == null || values.size() > MAX_INPUT_KINDS || (!allowEmpty && values.isEmpty())) {
            throw new IllegalArgumentException("invalid " + label + " map");
        }
        var copy = new TreeMap<String, Integer>();
        for (var entry : values.entrySet()) {
            TradeItem.requireId(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException(label + " must be positive");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(copy);
    }
}
