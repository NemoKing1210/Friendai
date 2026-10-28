package dev.friendai.features.trading;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Pure deterministic preview planner. A plan is informational and never reserves or spends inventory. */
public final class TradePlanner {
    private record OfferIdentity(List<TradeCost> inputs, TradeItem output) {
        private static OfferIdentity of(TradeOffer offer) {
            return new OfferIdentity(offer.baseInputs(), offer.output());
        }
    }

    private record Candidate(
            TraderRecord trader,
            TradeOffer offer,
            boolean quoteStale,
            boolean positionStale,
            boolean ambiguous,
            double distance) {}

    /** Optional current companion location used only to break equally priced/fresh route ties. */
    public record Location(String dimension, int x, int y, int z) {
        public Location {
            TradeItem.requireId(dimension);
        }
    }

    private TradePlanner() {}

    /** Defaults to exact output sizing; use the overload to explicitly authorize batch overshoot in a preview. */
    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick) {
        return planInternal(query, count, traders, inventory, budget, nowTick, false, null);
    }

    /**
     * Plans affordable whole vanilla fills for the query. BUY count measures selected output items; SELL count
     * measures the queried input item. Inputs are aggregated by item id, matching the item-id budgets. Component
     * predicates make the result conditional because exact stack identity is only checked by the executor.
     */
    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot) {
        return planInternal(query, count, traders, inventory, budget, nowTick, allowOvershoot, null);
    }

    /** Plans with deterministic freshness first and travel distance as the next route tiebreaker. */
    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            Location location) {
        return planInternal(query, count, traders, inventory, budget, nowTick, allowOvershoot, null, location);
    }

    /** Defaults to exact output sizing while checking quote ownership against the current pricing actor. */
    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            UUID pricingActor) {
        return planInternal(
                query,
                count,
                traders,
                inventory,
                budget,
                nowTick,
                false,
                Objects.requireNonNull(pricingActor, "pricing actor is required"));
    }

    /** Plans with explicit batch policy and quote ownership; this is still a preview, never authorization. */
    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            UUID pricingActor) {
        return planInternal(
                query,
                count,
                traders,
                inventory,
                budget,
                nowTick,
                allowOvershoot,
                Objects.requireNonNull(pricingActor, "pricing actor is required"),
                null);
    }

    /** Plans with actor-specific quote freshness and the current companion location for route tie-breaking. */
    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            UUID pricingActor,
            Location location) {
        return planInternal(
                query,
                count,
                traders,
                inventory,
                budget,
                nowTick,
                allowOvershoot,
                Objects.requireNonNull(pricingActor, "pricing actor is required"),
                location);
    }

    public static TradePlan plan(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            UUID pricingActor,
            Location location,
            long quoteFreshTicks,
            long locationFreshTicks) {
        if (quoteFreshTicks < 1 || locationFreshTicks < 1)
            throw new IllegalArgumentException("freshness windows must be positive");
        return planInternal(
                query,
                count,
                traders,
                inventory,
                budget,
                nowTick,
                allowOvershoot,
                Objects.requireNonNull(pricingActor, "pricing actor is required"),
                location,
                quoteFreshTicks,
                locationFreshTicks);
    }

    private static TradePlan planInternal(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            UUID pricingActor) {
        return planInternal(query, count, traders, inventory, budget, nowTick, allowOvershoot, pricingActor, null);
    }

    private static TradePlan planInternal(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            UUID pricingActor,
            Location location) {
        return planInternal(
                query,
                count,
                traders,
                inventory,
                budget,
                nowTick,
                allowOvershoot,
                pricingActor,
                location,
                TradeBook.QUOTE_FRESH_TICKS,
                TradeBook.POSITION_FRESH_TICKS);
    }

    private static TradePlan planInternal(
            TradeQuery query,
            int count,
            List<TraderRecord> traders,
            Map<String, Integer> inventory,
            TradeBudget budget,
            long nowTick,
            boolean allowOvershoot,
            UUID pricingActor,
            Location location,
            long quoteFreshTicks,
            long locationFreshTicks) {
        Objects.requireNonNull(query, "trade query is required");
        Objects.requireNonNull(traders, "trader observations are required");
        Objects.requireNonNull(inventory, "inventory counts are required");
        Objects.requireNonNull(budget, "trade budget is required");
        if (count < 1
                || count > TradeItem.MAX_COUNT
                || nowTick < 0
                || traders.size() > TradeFile.MAX_TRADERS
                || inventory.size() > TradeBudget.MAX_INPUT_KINDS) {
            throw new IllegalArgumentException("trade plan input is outside supported bounds");
        }
        validateInventory(inventory);
        List<Candidate> candidates =
                candidates(query, traders, nowTick, pricingActor, location, quoteFreshTicks, locationFreshTicks);
        var needsInspection = new TreeSet<java.util.UUID>();
        for (Candidate candidate : candidates) {
            if (candidate.quoteStale()
                    || candidate.positionStale()
                    || candidate.ambiguous()
                    || candidate.offer().exhausted()) {
                needsInspection.add(candidate.trader().id());
            }
        }

        var fills = new ArrayList<TradePlan.Fill>();
        var spent = new TreeMap<String, Long>();
        var plannedRepeats = new HashMap<Candidate, Long>();
        long remaining = count;
        boolean conditional = !needsInspection.isEmpty();

        for (Candidate candidate : candidates) {
            if (remaining <= 0 || candidate.offer().exhausted() || candidate.ambiguous()) continue;
            TradeOffer offer = candidate.offer();
            Map<String, Long> costPerRepeat = costPerRepeat(offer.effectiveInputs());
            if (costPerRepeat.isEmpty() || violatesUnitCostCaps(offer, costPerRepeat, budget)) continue;
            long unitsPerRepeat = unitsPerRepeat(query, offer);
            if (unitsPerRepeat <= 0) continue;
            long requestedRepeats = repeatsFor(remaining, unitsPerRepeat, allowOvershoot);
            if (requestedRepeats <= 0) continue;

            long maximumRepeats = Math.min(requestedRepeats, offer.maxUses() - (long) offer.uses());
            for (var cost : costPerRepeat.entrySet()) {
                Integer cap = budget.inputCaps().get(cost.getKey());
                if (cap == null) {
                    maximumRepeats = 0;
                    break;
                }
                long alreadySpent = spent.getOrDefault(cost.getKey(), 0L);
                long cappedAvailable = Math.max(0L, cap - alreadySpent);
                maximumRepeats = Math.min(maximumRepeats, cappedAvailable / cost.getValue());

                long reserve = budget.reserves().getOrDefault(cost.getKey(), 0);
                long inBag = inventory.getOrDefault(cost.getKey(), 0);
                long spendable = Math.max(0L, inBag - reserve - alreadySpent);
                maximumRepeats = Math.min(maximumRepeats, spendable / cost.getValue());
            }
            if (maximumRepeats <= 0) continue;

            int repeats = Math.toIntExact(maximumRepeats);
            fills.add(new TradePlan.Fill(
                    candidate.trader().id(),
                    offer.ref(),
                    candidate.trader().revision(),
                    repeats,
                    offer.effectiveInputs(),
                    offer.output()));
            plannedRepeats.put(candidate, maximumRepeats);
            for (var cost : costPerRepeat.entrySet()) {
                spent.merge(cost.getKey(), Math.multiplyExact(cost.getValue(), maximumRepeats), Math::addExact);
            }
            long completedUnits = Math.multiplyExact(unitsPerRepeat, maximumRepeats);
            remaining = Math.max(0L, remaining - completedUnits);
            conditional |= candidate.quoteStale()
                    || candidate.positionStale()
                    || offer.effectiveInputs().stream()
                            .anyMatch(input -> !input.predicateJson().equals("{}"));
        }

        Map<String, Integer> missing = remaining == 0
                ? Map.of()
                : missingForNextRoute(
                        query, remaining, candidates, inventory, budget, spent, plannedRepeats, allowOvershoot);
        return new TradePlan(fills, missing, List.copyOf(needsInspection), conditional);
    }

    private static List<Candidate> candidates(
            TradeQuery query,
            List<TraderRecord> traders,
            long nowTick,
            UUID pricingActor,
            Location location,
            long quoteFreshTicks,
            long locationFreshTicks) {
        var result = new ArrayList<Candidate>();
        var seenTraders = new HashSet<java.util.UUID>();
        for (TraderRecord trader : traders) {
            Objects.requireNonNull(trader, "trader observation is required");
            if (!seenTraders.add(trader.id()))
                throw new IllegalArgumentException("duplicate trader UUID in planning input");
            if (trader.status() == TraderRecord.Status.DEAD_CONFIRMED || trader.status() == TraderRecord.Status.EXPIRED)
                continue;
            var duplicateCounts = new HashMap<OfferIdentity, Integer>();
            for (TradeOffer offer : trader.offers()) duplicateCounts.merge(OfferIdentity.of(offer), 1, Integer::sum);
            boolean positionStale = stale(nowTick, trader.lastSeen(), locationFreshTicks);
            for (TradeOffer offer : trader.offers()) {
                if (!query.matches(offer)) continue;
                boolean quoteStale = trader.status() != TraderRecord.Status.VERIFIED
                        || (pricingActor != null && !offer.pricingActor().equals(pricingActor))
                        || stale(nowTick, offer.observedTick(), quoteFreshTicks);
                boolean ambiguous = duplicateCounts.getOrDefault(OfferIdentity.of(offer), 0) > 1;
                result.add(
                        new Candidate(trader, offer, quoteStale, positionStale, ambiguous, distance(location, trader)));
            }
        }
        result.sort(Comparator.comparing(Candidate::quoteStale)
                .thenComparing(candidate -> candidate.offer().exhausted())
                .thenComparing(Candidate::positionStale)
                .thenComparing((Candidate candidate) -> candidate.offer().observedTick(), Comparator.reverseOrder())
                .thenComparing((Candidate candidate) -> candidate.trader().lastSeen(), Comparator.reverseOrder())
                .thenComparingDouble(Candidate::distance)
                .thenComparing(candidate -> candidate.trader().id())
                .thenComparing(candidate -> candidate.offer().fingerprint())
                .thenComparing(candidate -> candidate.offer().ref()));
        if (result.size() > query.limit()) return List.copyOf(result.subList(0, query.limit()));
        return List.copyOf(result);
    }

    private static double distance(Location location, TraderRecord trader) {
        if (location == null) return 0;
        if (!location.dimension().equals(trader.dimension())) return Double.POSITIVE_INFINITY;
        double dx = (double) location.x() - trader.x();
        double dy = (double) location.y() - trader.y();
        double dz = (double) location.z() - trader.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Map<String, Long> costPerRepeat(List<TradeCost> inputs) {
        var result = new TreeMap<String, Long>();
        for (TradeCost input : inputs) result.merge(input.itemId(), (long) input.count(), Math::addExact);
        return result;
    }

    private static boolean violatesUnitCostCaps(TradeOffer offer, Map<String, Long> costs, TradeBudget budget) {
        for (var entry : costs.entrySet()) {
            Integer maximum = budget.maxUnitCosts().get(entry.getKey());
            if (maximum == null) continue;
            long allowed = Math.multiplyExact((long) maximum, offer.output().count());
            if (entry.getValue() > allowed) return true;
        }
        return false;
    }

    private static long unitsPerRepeat(TradeQuery query, TradeOffer offer) {
        if (query.direction() == TradeQuery.Direction.BUY) return offer.output().count();
        long matchingInput = 0;
        var requirements =
                JsonParser.parseString(query.requiredComponentsJson()).getAsJsonObject();
        for (TradeCost input : offer.effectiveInputs()) {
            if (query.itemId().equals(input.itemId()) && componentsMatch(requirements, input.predicateJson())) {
                matchingInput = Math.addExact(matchingInput, input.count());
            }
        }
        return matchingInput;
    }

    private static boolean componentsMatch(com.google.gson.JsonObject requirements, String predicateJson) {
        var predicate = JsonParser.parseString(predicateJson).getAsJsonObject();
        return requirements.entrySet().stream()
                .allMatch(entry ->
                        predicate.has(entry.getKey()) && entry.getValue().equals(predicate.get(entry.getKey())));
    }

    private static long repeatsFor(long remaining, long unitsPerRepeat, boolean allowOvershoot) {
        if (unitsPerRepeat <= 0) return 0;
        long repeats = remaining / unitsPerRepeat;
        if (allowOvershoot && remaining % unitsPerRepeat != 0) repeats++;
        return repeats;
    }

    private static Map<String, Integer> missingForNextRoute(
            TradeQuery query,
            long remaining,
            List<Candidate> candidates,
            Map<String, Integer> inventory,
            TradeBudget budget,
            Map<String, Long> spent,
            Map<Candidate, Long> plannedRepeats,
            boolean allowOvershoot) {
        for (Candidate candidate : candidates) {
            TradeOffer offer = candidate.offer();
            if (offer.exhausted() || candidate.ambiguous()) continue;
            Map<String, Long> costs = costPerRepeat(offer.effectiveInputs());
            if (costs.isEmpty() || violatesUnitCostCaps(offer, costs, budget)) continue;
            long unitsPerRepeat = unitsPerRepeat(query, offer);
            long wantedRepeats = repeatsFor(remaining, unitsPerRepeat, allowOvershoot);
            if (wantedRepeats <= 0) continue;

            long routeRepeats = Math.min(
                    wantedRepeats, offer.maxUses() - (long) offer.uses() - plannedRepeats.getOrDefault(candidate, 0L));
            for (var cost : costs.entrySet()) {
                Integer cap = budget.inputCaps().get(cost.getKey());
                if (cap == null) {
                    routeRepeats = 0;
                    break;
                }
                long capAvailable = Math.max(0L, cap - spent.getOrDefault(cost.getKey(), 0L));
                routeRepeats = Math.min(routeRepeats, capAvailable / cost.getValue());
            }
            if (routeRepeats <= 0) continue;

            var missing = new TreeMap<String, Integer>();
            for (var cost : costs.entrySet()) {
                long required = Math.multiplyExact(cost.getValue(), routeRepeats);
                long reserve = budget.reserves().getOrDefault(cost.getKey(), 0);
                long spendable = Math.max(
                        0L, inventory.getOrDefault(cost.getKey(), 0) - reserve - spent.getOrDefault(cost.getKey(), 0L));
                long shortage = Math.max(0L, required - spendable);
                if (shortage > 0) missing.put(cost.getKey(), checkedMissing(shortage));
            }
            if (!missing.isEmpty()) return java.util.Collections.unmodifiableMap(missing);
        }
        return Map.of();
    }

    private static int checkedMissing(long count) {
        if (count > Integer.MAX_VALUE)
            throw new IllegalArgumentException("trade plan shortage exceeds supported count");
        return (int) count;
    }

    private static boolean stale(long nowTick, long observedTick, long freshTicks) {
        return observedTick < 0 || nowTick < observedTick || nowTick - observedTick >= freshTicks;
    }

    private static void validateInventory(Map<String, Integer> inventory) {
        for (var entry : inventory.entrySet()) {
            TradeItem.requireId(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("inventory item-id totals must be nonnegative");
            }
        }
    }
}
