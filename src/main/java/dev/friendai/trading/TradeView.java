package dev.friendai.trading;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Sanitized bounded page for the owner's Traders tab. */
public record TradeView(
        UUID queryId,
        long revision,
        int page,
        int totalPages,
        List<TraderRecord> traders,
        List<TradeOffer> offers,
        long serverTick,
        long quoteFreshTicks) {
    public static final int MAX_TRADERS = 20;
    public static final int MAX_OFFERS = 40;
    public static final int MAX_PACKET_BYTES = 64 * 1024;

    public TradeView {
        if (queryId == null
                || revision < 0
                || page < 0
                || totalPages < 1
                || totalPages > 256
                || page >= totalPages
                || serverTick < 0
                || quoteFreshTicks < 1
                || traders == null
                || traders.size() > MAX_TRADERS
                || offers == null
                || offers.size() > MAX_OFFERS) {
            throw new IllegalArgumentException("invalid trade view page");
        }
        traders = List.copyOf(traders);
        offers = List.copyOf(offers);
        var ids = new HashSet<UUID>();
        if (traders.stream().anyMatch(trader -> trader == null || !ids.add(trader.id())))
            throw new IllegalArgumentException("trade view trader ids must be unique");
        if (offers.stream().anyMatch(offer -> offer == null))
            throw new IllegalArgumentException("trade view has null offer");
    }

    public TradeView(
            UUID queryId,
            long revision,
            int page,
            int totalPages,
            List<TraderRecord> traders,
            List<TradeOffer> offers) {
        this(queryId, revision, page, totalPages, traders, offers, 0, 1_200);
    }
}
