package dev.friendai.net;

import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TradeQuery;
import dev.friendai.features.trading.TradeView;
import dev.friendai.features.trading.TraderRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Builds and sends a bounded private page from only the authenticated player's own market book. */
public final class TradeViewStream {
    private TradeViewStream() {}

    public static void send(ServerPlayer player, UUID queryId, int page, TradeQuery query) {
        var book = dev.friendai.core.FriendaiServerState.get(player.level().getServer())
                .trading()
                .book(player.getUUID());
        var tradingSettings = dev.friendai.core.FriendaiServerState.get(
                        player.level().getServer())
                .config()
                .tasks
                .trading;
        long serverTick = player.level().getServer().overworld().getGameTime();
        var records = book.snapshot().traders().stream()
                .sorted(Comparator.comparing(TraderRecord::id))
                .toList();
        List<TraderRecord> selected;
        List<TradeOffer> offers;
        int totalPages;
        if (query == null) {
            totalPages = Math.max(1, (records.size() + TradeView.MAX_TRADERS - 1) / TradeView.MAX_TRADERS);
            int from = Math.min(records.size(), page * TradeView.MAX_TRADERS);
            selected = records.subList(from, Math.min(records.size(), from + TradeView.MAX_TRADERS));
            offers = selected.stream()
                    .flatMap(record -> record.offers().stream())
                    .limit(TradeView.MAX_OFFERS)
                    .toList();
        } else {
            var matching = new ArrayList<TraderRecord>();
            var matchedOffers = new ArrayList<TradeOffer>();
            for (TraderRecord record : records) {
                List<TradeOffer> found =
                        record.offers().stream().filter(query::matches).toList();
                if (!found.isEmpty()) {
                    matching.add(record);
                    matchedOffers.addAll(found);
                }
            }
            totalPages = Math.max(1, (matchedOffers.size() + TradeView.MAX_OFFERS - 1) / TradeView.MAX_OFFERS);
            int from = Math.min(matchedOffers.size(), page * TradeView.MAX_OFFERS);
            offers = List.copyOf(
                    matchedOffers.subList(from, Math.min(matchedOffers.size(), from + TradeView.MAX_OFFERS)));
            List<TradeOffer> pageOffers = offers;
            selected = matching.stream()
                    .filter(record -> pageOffers.stream()
                            .anyMatch(offer -> record.offers().stream()
                                    .anyMatch(candidate -> candidate.ref().equals(offer.ref()))))
                    .limit(TradeView.MAX_TRADERS)
                    .toList();
        }
        if (page < 0 || page >= totalPages) return;
        List<TraderRecord> visible = trimRecords(selected, offers);
        List<TradeOffer> visibleOffers =
                visible.stream().flatMap(record -> record.offers().stream()).toList();
        TradeView view = new TradeView(
                queryId,
                book.revision(),
                page,
                totalPages,
                visible,
                visibleOffers,
                serverTick,
                tradingSettings.quoteFreshTicks);
        if (ServerPlayNetworking.canSend(player, TradeViewS2CPayload.TYPE)) {
            while (true) {
                try {
                    ServerPlayNetworking.send(player, new TradeViewS2CPayload(view));
                    return;
                } catch (IllegalArgumentException tooLarge) {
                    if (!visibleOffers.isEmpty()) visibleOffers = visibleOffers.subList(0, visibleOffers.size() - 1);
                    else if (!visible.isEmpty()) visible = visible.subList(0, visible.size() - 1);
                    else return;
                    visible = trimRecords(selected, visibleOffers);
                    visibleOffers = visible.stream()
                            .flatMap(record -> record.offers().stream())
                            .toList();
                    view = new TradeView(
                            queryId,
                            book.revision(),
                            page,
                            totalPages,
                            visible,
                            visibleOffers,
                            serverTick,
                            tradingSettings.quoteFreshTicks);
                }
            }
        }
    }

    private static List<TraderRecord> trimRecords(List<TraderRecord> records, List<TradeOffer> offers) {
        return records.stream()
                .map(record -> {
                    List<TradeOffer> visible = record.offers().stream()
                            .filter(candidate -> offers.stream()
                                    .anyMatch(pageOffer -> candidate.ref().equals(pageOffer.ref())
                                            && candidate.fingerprint().equals(pageOffer.fingerprint())))
                            .toList();
                    return new TraderRecord(
                            record.id(),
                            record.entityType(),
                            record.dimension(),
                            record.x(),
                            record.y(),
                            record.z(),
                            record.label(),
                            record.profession(),
                            record.level(),
                            record.firstSeen(),
                            record.lastSeen(),
                            record.offersSeen(),
                            record.revision(),
                            record.status(),
                            visible);
                })
                .filter(record -> !record.offers().isEmpty()
                        || record.entityType().equals("minecraft:piglin")
                        || record.status() != TraderRecord.Status.VERIFIED)
                .toList();
    }
}
