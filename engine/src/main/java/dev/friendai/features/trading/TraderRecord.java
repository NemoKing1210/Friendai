package dev.friendai.features.trading;

import java.util.List;
import java.util.UUID;

/** UUID is identity; mutable dimension/coordinates are only a last-observed search hint. */
public record TraderRecord(
        UUID id,
        String entityType,
        String dimension,
        int x,
        int y,
        int z,
        String label,
        String profession,
        int level,
        long firstSeen,
        long lastSeen,
        long offersSeen,
        long revision,
        Status status,
        List<TradeOffer> offers) {
    public enum Status {
        SEEN,
        VERIFIED,
        BUSY,
        NOT_FOUND,
        UNREACHABLE,
        DEAD_CONFIRMED,
        EXPIRED
    }

    public TraderRecord {
        if (id == null || status == null) throw new IllegalArgumentException("trader needs UUID and status");
        TradeItem.requireId(entityType);
        TradeItem.requireId(dimension);
        label = TradeItem.requireText(label, 80);
        if (profession == null) throw new IllegalArgumentException("profession must not be null");
        if (!profession.isEmpty()) TradeItem.requireId(profession);
        if (level < 0
                || level > 5
                || firstSeen < 0
                || lastSeen < firstSeen
                || revision < 0
                || offersSeen < -1
                || offersSeen > lastSeen
                || (offersSeen >= 0 && offersSeen < firstSeen)) {
            throw new IllegalArgumentException("invalid trader observation metadata");
        }
        if (offers == null || offers.size() > TradeFile.MAX_OFFERS_PER_TRADER) {
            throw new IllegalArgumentException("trader offer limit exceeded");
        }
        offers = List.copyOf(offers);
        for (var offer : offers) {
            if (offersSeen < 0 || offer.observedTick() > offersSeen) {
                throw new IllegalArgumentException("offer is newer than inspection");
            }
        }
    }
}
