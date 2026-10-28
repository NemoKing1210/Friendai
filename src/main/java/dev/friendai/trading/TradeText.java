package dev.friendai.trading;

import java.util.stream.Collectors;

/** English diagnostic/LLM text only. Player UI adapters supply localized labels separately. */
public final class TradeText {
    private TradeText() {}

    public static String describe(TradeQuery.Match match) {
        var offer = match.offer();
        String inputs = offer.effectiveInputs().stream()
                .map(input -> input.count() + " " + input.itemId() + " predicate=" + input.predicateJson())
                .collect(Collectors.joining(" + "));
        return describe(match.trader()) + "; " + offer.ref() + ": " + inputs + " -> "
                + offer.output().count() + " " + offer.output().itemId() + " components="
                + offer.output().componentsJson()
                + "; quote=" + (match.quoteStale() ? "stale" : "current (live validation required)")
                + "; position=" + (match.positionStale() ? "stale last seen" : "last seen")
                + "; stock=" + (offer.exhausted() ? "exhausted" : (offer.maxUses() - offer.uses()) + " remaining")
                + (match.ambiguous() ? "; ambiguous duplicate: inspection required" : "");
    }

    public static String describe(TraderRecord trader) {
        return (trader.label().isEmpty() ? trader.entityType() : trader.label()) + " [" + trader.id() + "] "
                + trader.status().name() + "; last seen=" + trader.lastSeen() + " at " + trader.dimension() + " "
                + trader.x() + "," + trader.y() + "," + trader.z()
                + (trader.offersSeen() < 0 ? "; offers=unknown" : "; offers observed=" + trader.offersSeen());
    }
}
