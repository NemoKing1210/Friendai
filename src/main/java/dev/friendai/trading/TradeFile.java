package dev.friendai.trading;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable version-1 persistence data. Runtime actor identity and active references are excluded. */
public record TradeFile(int version, long revision, List<TraderRecord> traders,
                        List<TradeProgress> history, Set<UUID> pinned) {
    public static final int VERSION = 1;
    public static final int MAX_TRADERS = 256;
    public static final int MAX_OFFERS_PER_TRADER = 64;
    public static final int MAX_TOTAL_OFFERS = 2048;
    public static final int MAX_HISTORY = 256;
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    public TradeFile {
        if (version != VERSION || revision < 0 || traders == null || traders.size() > MAX_TRADERS
                || history == null || history.size() > MAX_HISTORY || pinned == null || pinned.size() > MAX_TRADERS) {
            throw new IllegalArgumentException("invalid or oversized trade file");
        }
        // Validate all aggregate counts before copying the caller's collections.
        int offerCount = 0;
        var ids = new HashSet<UUID>();
        for (var trader : traders) {
            if (trader == null || !ids.add(trader.id())) throw new IllegalArgumentException("duplicate trader UUID");
            offerCount += trader.offers().size();
            if (offerCount > MAX_TOTAL_OFFERS) throw new IllegalArgumentException("total offer limit exceeded");
        }
        if (!ids.containsAll(pinned)) throw new IllegalArgumentException("pin refers to unknown trader");
        var orders = new HashSet<UUID>();
        for (var progress : history) {
            if (progress == null || !orders.add(progress.orderId())) throw new IllegalArgumentException("duplicate order UUID");
        }
        traders = List.copyOf(traders);
        history = List.copyOf(history);
        pinned = Set.copyOf(pinned);
    }
}
