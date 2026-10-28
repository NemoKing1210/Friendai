package dev.friendai.trading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Per-owner bounded observations. Call on the owning server thread; no world or registry access. */
public final class TradeBook {
    public static final long QUOTE_FRESH_TICKS = 1200;
    public static final long POSITION_FRESH_TICKS = 12000;
    private final Map<UUID, TraderRecord> traders = new HashMap<>();
    private final LinkedHashMap<UUID, TradeProgress> history = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, BarterProgress> barterHistory = new LinkedHashMap<>();
    private final Set<UUID> pinned = new HashSet<>();
    private final Map<UUID, Integer> retained = new HashMap<>();
    private UUID pricingActor;
    private long revision;

    private record OfferIdentity(List<TradeCost> inputs, TradeItem output) {
        static OfferIdentity of(TradeOffer offer) {
            return new OfferIdentity(offer.baseInputs(), offer.output());
        }
    }

    public TradeBook() {}

    public TradeBook(TradeFile saved) {
        Objects.requireNonNull(saved);
        saved.traders().forEach(trader -> traders.put(trader.id(), stableOffers(trader)));
        saved.history().forEach(progress -> history.put(progress.orderId(), progress));
        saved.barterHistory().forEach(progress -> barterHistory.put(progress.orderId(), progress));
        pinned.addAll(saved.pinned());
        revision = saved.revision();
    }

    /** Activating a replacement companion immediately invalidates personalized historical quotes. */
    public void activatePricingActor(UUID actor) {
        Objects.requireNonNull(actor);
        if (!actor.equals(pricingActor)) {
            changed();
            pricingActor = actor;
        }
    }

    /** False means capacity is protected. Rejection never evicts or partially updates anything. */
    public boolean observe(TraderRecord observation) {
        Objects.requireNonNull(observation);
        var old = traders.get(observation.id());
        if (old != null
                && (observation.lastSeen() < old.lastSeen()
                        || (observation.status() == TraderRecord.Status.VERIFIED
                                && observation.offersSeen() >= 0
                                && observation.offersSeen() < old.offersSeen()))) {
            throw new IllegalArgumentException("backward trader observation");
        }
        boolean complete = observation.status() == TraderRecord.Status.VERIFIED && observation.offersSeen() >= 0;
        List<TradeOffer> offers = complete ? observation.offers() : old == null ? List.of() : old.offers();
        long offersSeen = complete ? observation.offersSeen() : old == null ? -1 : old.offersSeen();
        validateFingerprints(old, offers);
        long firstSeen = old == null ? observation.firstSeen() : Math.min(old.firstSeen(), observation.firstSeen());
        var next = stableOffers(new TraderRecord(
                observation.id(),
                observation.entityType(),
                observation.dimension(),
                observation.x(),
                observation.y(),
                observation.z(),
                observation.label(),
                observation.profession(),
                observation.level(),
                firstSeen,
                observation.lastSeen(),
                offersSeen,
                Math.incrementExact(old == null ? 0 : old.revision()),
                observation.status(),
                offers));
        int totalOffers =
                traders.values().stream().mapToInt(t -> t.offers().size()).sum()
                        - (old == null ? 0 : old.offers().size())
                        + next.offers().size();
        int totalTraders = traders.size() + (old == null ? 1 : 0);
        var candidates = traders.values().stream()
                .filter(t -> !t.id().equals(next.id()) && !pinned.contains(t.id()) && !retained.containsKey(t.id()))
                .sorted(Comparator.comparingLong(TraderRecord::lastSeen).thenComparing(TraderRecord::id))
                .toList();
        var evict = new ArrayList<UUID>();
        for (var candidate : candidates) {
            if (totalOffers <= TradeFile.MAX_TOTAL_OFFERS && totalTraders <= TradeFile.MAX_TRADERS) break;
            evict.add(candidate.id());
            totalTraders--;
            totalOffers -= candidate.offers().size();
        }
        if (totalOffers > TradeFile.MAX_TOTAL_OFFERS || totalTraders > TradeFile.MAX_TRADERS) return false;
        changed();
        evict.forEach(traders::remove);
        traders.put(next.id(), next);
        return true;
    }

    private static void validateFingerprints(TraderRecord previous, List<TradeOffer> offers) {
        var identities = new HashMap<String, OfferIdentity>();
        if (previous != null) {
            for (var offer : previous.offers()) identities.put(offer.fingerprint(), OfferIdentity.of(offer));
        }
        for (var offer : offers) {
            var identity = OfferIdentity.of(offer);
            var old = identities.putIfAbsent(offer.fingerprint(), identity);
            if (old != null && !old.equals(identity)) {
                throw new IllegalArgumentException("semantic fingerprint reused for different offer");
            }
        }
    }

    private static TraderRecord stableOffers(TraderRecord trader) {
        validateFingerprints(null, trader.offers());
        return new TraderRecord(
                trader.id(),
                trader.entityType(),
                trader.dimension(),
                trader.x(),
                trader.y(),
                trader.z(),
                trader.label(),
                trader.profession(),
                trader.level(),
                trader.firstSeen(),
                trader.lastSeen(),
                trader.offersSeen(),
                trader.revision(),
                trader.status(),
                trader.offers().stream().map(TradeOffer::withStableRef).toList());
    }

    public Optional<TraderRecord> trader(UUID id) {
        return Optional.ofNullable(traders.get(id));
    }

    /** Removes one remembered partner unless a caller has pinned/retained it for an active action. */
    public boolean forget(UUID id) {
        if (id == null || pinned.contains(id) || retained.containsKey(id) || traders.remove(id) == null) return false;
        changed();
        return true;
    }

    /** Ambiguous duplicates must be inspected, never selected by a remembered list index. */
    public Optional<TradeOffer> resolveOffer(UUID traderId, String ref) {
        var trader = traders.get(traderId);
        if (trader == null) return Optional.empty();
        return trader.offers().stream()
                .filter(offer -> offer.ref().equals(ref))
                .filter(offer -> !ambiguous(trader, offer))
                .findFirst();
    }

    public List<TradeQuery.Match> query(TradeQuery query, long nowTick) {
        return query(query, nowTick, QUOTE_FRESH_TICKS, POSITION_FRESH_TICKS);
    }

    public List<TradeQuery.Match> query(TradeQuery query, long nowTick, long quoteFreshTicks, long locationFreshTicks) {
        Objects.requireNonNull(query);
        if (quoteFreshTicks < 1 || locationFreshTicks < 1)
            throw new IllegalArgumentException("freshness windows must be positive");
        var matches = new ArrayList<TradeQuery.Match>();
        for (var trader : traders.values()) {
            boolean positionStale = stale(nowTick, trader.lastSeen(), locationFreshTicks);
            var duplicates = new HashMap<OfferIdentity, Integer>();
            for (var offer : trader.offers()) duplicates.merge(OfferIdentity.of(offer), 1, Integer::sum);
            for (var offer : trader.offers()) {
                if (!query.matches(offer)) continue;
                boolean quoteStale = trader.status() != TraderRecord.Status.VERIFIED
                        || !offer.pricingActor().equals(pricingActor)
                        || stale(nowTick, offer.observedTick(), quoteFreshTicks);
                matches.add(new TradeQuery.Match(
                        trader, offer, quoteStale, positionStale, duplicates.get(OfferIdentity.of(offer)) > 1));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparing(TradeQuery.Match::quoteStale)
                        .thenComparing(m -> m.offer().exhausted())
                        .thenComparing(TradeQuery.Match::positionStale)
                        .thenComparing(m -> m.trader().id())
                        .thenComparing(m -> m.offer().fingerprint()))
                .limit(query.limit())
                .toList();
    }

    private static boolean ambiguous(TraderRecord trader, TradeOffer selected) {
        var identity = OfferIdentity.of(selected);
        return trader.offers().stream()
                        .filter(offer -> OfferIdentity.of(offer).equals(identity))
                        .limit(2)
                        .count()
                > 1;
    }

    private static boolean stale(long now, long observed, long lifetime) {
        return observed < 0 || now < observed || now - observed >= lifetime;
    }

    public void pin(UUID id) {
        requireTrader(id);
        if (!pinned.contains(id)) {
            changed();
            pinned.add(id);
        }
    }

    public void unpin(UUID id) {
        if (pinned.contains(Objects.requireNonNull(id))) {
            changed();
            pinned.remove(id);
        }
    }

    /** Runtime-only reference counts let concurrent inspection/order owners release independently. */
    public void retain(UUID id) {
        requireTrader(id);
        retained.merge(id, 1, Math::addExact);
    }

    public void release(UUID id) {
        Objects.requireNonNull(id);
        retained.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
    }

    /** Detached immutable protection set for bounded persistence eviction. */
    public Set<UUID> protectedTraders() {
        var protectedIds = new HashSet<>(pinned);
        protectedIds.addAll(retained.keySet());
        return Set.copyOf(protectedIds);
    }

    /** Remove exactly one oldest unprotected historical record, with UUID as a stable tie-breaker. */
    public boolean evictOldestUnprotected() {
        var candidate = traders.values().stream()
                .filter(trader -> !pinned.contains(trader.id()) && !retained.containsKey(trader.id()))
                .min(Comparator.comparingLong(TraderRecord::lastSeen).thenComparing(TraderRecord::id));
        if (candidate.isEmpty()) return false;
        changed();
        traders.remove(candidate.get().id());
        return true;
    }

    /** Remove the oldest terminal summary; live authorization and running progress are never evicted. */
    public boolean evictOldestTerminalProgress() {
        var candidate = history.entrySet().stream()
                .filter(entry -> entry.getValue().state() != TradeProgress.State.AUTHORIZED
                        && entry.getValue().state() != TradeProgress.State.RUNNING)
                .findFirst();
        if (candidate.isEmpty()) return false;
        changed();
        history.remove(candidate.get().getKey());
        return true;
    }

    private void requireTrader(UUID id) {
        if (!traders.containsKey(Objects.requireNonNull(id))) throw new IllegalArgumentException("unknown trader UUID");
    }

    public void record(TradeProgress progress) {
        Objects.requireNonNull(progress);
        if (progress.equals(history.get(progress.orderId()))) return;
        var terminalToEvict = history.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(progress.orderId()))
                .filter(entry -> entry.getValue().state() != TradeProgress.State.AUTHORIZED
                        && entry.getValue().state() != TradeProgress.State.RUNNING)
                .findFirst();
        if (!history.containsKey(progress.orderId())
                && history.size() >= TradeFile.MAX_HISTORY
                && terminalToEvict.isEmpty()) {
            throw new IllegalStateException("trade history capacity is occupied by active orders");
        }
        changed();
        if (!history.containsKey(progress.orderId()) && history.size() >= TradeFile.MAX_HISTORY) {
            history.remove(terminalToEvict.orElseThrow().getKey());
        }
        history.remove(progress.orderId());
        history.put(progress.orderId(), progress);
    }

    public void recordBarter(BarterProgress progress) {
        Objects.requireNonNull(progress);
        BarterProgress previous = barterHistory.get(progress.orderId());
        if (progress.equals(previous)) return;
        if (previous != null
                && (progress.goldAccepted() < previous.goldAccepted()
                        || !progress.emittedItems().containsAll(previous.emittedItems())
                        || progress.collected().size() < previous.collected().size()
                        || (previous.terminal() && !progress.terminal()))) {
            throw new IllegalArgumentException("barter history cannot move backward");
        }
        var terminalToEvict = barterHistory.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(progress.orderId()))
                .filter(entry -> entry.getValue().terminal())
                .findFirst();
        if (previous == null && barterHistory.size() >= TradeFile.MAX_HISTORY && terminalToEvict.isEmpty()) {
            throw new IllegalStateException("barter history capacity is occupied by active orders");
        }
        changed();
        if (previous == null && barterHistory.size() >= TradeFile.MAX_HISTORY) {
            barterHistory.remove(terminalToEvict.orElseThrow().getKey());
        }
        barterHistory.remove(progress.orderId());
        barterHistory.put(progress.orderId(), progress);
    }

    public Optional<BarterProgress> barterProgress(UUID orderId) {
        return Optional.ofNullable(barterHistory.get(orderId));
    }

    public long revision() {
        return revision;
    }

    public TradeFile snapshot() {
        return new TradeFile(
                TradeFile.VERSION,
                revision,
                traders.values().stream()
                        .sorted(Comparator.comparing(TraderRecord::id))
                        .toList(),
                List.copyOf(history.values()),
                pinned,
                List.copyOf(barterHistory.values()));
    }

    private void changed() {
        revision = Math.incrementExact(revision);
    }
}
