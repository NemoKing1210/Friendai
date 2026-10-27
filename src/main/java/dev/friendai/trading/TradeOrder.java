package dev.friendai.trading;

import java.util.Set;
import java.util.UUID;

/** Immutable requested trade bound to a server-issued owner scope. */
public record TradeOrder(UUID orderId, UUID owner, UUID scopeId, UUID trader, String offerRef,
                         long observationRevision, int desiredOutputCount, TradeBudget budget,
                         Set<String> allowedPaymentFingerprints, boolean allowOvershoot,
                         boolean allowPartial, long deadlineTick) {
    public TradeOrder {
        if (orderId == null || owner == null || scopeId == null || trader == null || budget == null
                || observationRevision < 0 || desiredOutputCount < 1 || deadlineTick < 0) {
            throw new IllegalArgumentException("invalid trade order");
        }
        offerRef = TradeItem.requireText(offerRef, 160);
        if (offerRef.isBlank()) throw new IllegalArgumentException("trade order needs an offer reference");
        if (allowedPaymentFingerprints == null || allowedPaymentFingerprints.size() > 256
                || allowedPaymentFingerprints.stream().anyMatch(value -> value == null || !value.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("invalid allowed payment fingerprints");
        }
        allowedPaymentFingerprints = Set.copyOf(allowedPaymentFingerprints);
    }
}
