package dev.friendai.trading;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable, non-authorizing preview of the fills and payment resources for a trade query. */
public record TradePlan(List<Fill> fills, Map<String, Integer> missing,
                        List<UUID> needsInspection, boolean conditional) {
    public TradePlan {
        if (fills == null || fills.size() > TradeQuery.MAX_RESULTS
                || missing == null || missing.size() > TradeBudget.MAX_INPUT_KINDS
                || needsInspection == null || needsInspection.size() > TradeFile.MAX_TRADERS) {
            throw new IllegalArgumentException("invalid trade plan bounds");
        }
        fills = List.copyOf(fills);
        var missingCopy = new TreeMap<String, Integer>();
        for (var entry : missing.entrySet()) {
            TradeItem.requireId(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("missing resource counts must be positive");
            }
            missingCopy.put(entry.getKey(), entry.getValue());
        }
        missing = Collections.unmodifiableMap(missingCopy);
        var inspectionCopy = List.copyOf(needsInspection);
        var uniqueInspection = new HashSet<UUID>();
        if (inspectionCopy.stream().anyMatch(id -> id == null || !uniqueInspection.add(id))) {
            throw new IllegalArgumentException("inspection trader ids must be unique and non-null");
        }
        needsInspection = inspectionCopy;
    }

    /** A deterministic suggested number of identical offer fills; execution still revalidates each one. */
    public record Fill(UUID trader, String offerRef, long revision, int repeats,
                       List<TradeCost> inputs, TradeItem output) {
        public Fill {
            if (trader == null || revision < 0 || repeats < 1 || repeats > TradeItem.MAX_COUNT
                    || inputs == null || inputs.isEmpty() || inputs.size() > 2 || output == null) {
                throw new IllegalArgumentException("invalid planned trade fill");
            }
            TradeItem.requireText(offerRef, 160);
            if (offerRef.isBlank()) throw new IllegalArgumentException("planned fill needs an offer reference");
            inputs = List.copyOf(inputs);
        }
    }
}
