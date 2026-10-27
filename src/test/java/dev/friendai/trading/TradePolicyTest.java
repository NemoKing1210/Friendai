package dev.friendai.trading;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.trading.TradeProgress.State;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradePolicyTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID SCOPE = new UUID(0, 2);
    private static final UUID TRADER = new UUID(0, 3);

    @Test void cumulativeSpendIsSharedAcrossRetriesAndFourFillsExhaustCap() {
        var budget = new TradeBudget(Map.of("minecraft:emerald", 10), Map.of(), Map.of());
        var offer = offer(new TradeCost("minecraft:emerald", "{}", 3), 1);
        var order = order(budget, 4, false);

        assertAllowed(TradePolicy.check(order, offer, Map.of("minecraft:emerald", 10), progress(order, 0), true, 1));
        assertAllowed(TradePolicy.check(order, offer, Map.of("minecraft:emerald", 7), progress(order, 3), true, 2));
        assertAllowed(TradePolicy.check(order, offer, Map.of("minecraft:emerald", 4), progress(order, 6), true, 3));
        var denied = TradePolicy.check(order, offer, Map.of("minecraft:emerald", 1), progress(order, 9), true, 4);
        assertFalse(denied.allowed());
        assertEquals(0, denied.repeats());
    }

    @Test void reserveAndMultipleInputsAreCheckedTogether() {
        var budget = new TradeBudget(Map.of("minecraft:emerald", 10, "minecraft:book", 2),
                Map.of("minecraft:emerald", 4, "minecraft:book", 1), Map.of());
        var order = order(budget, 1, false);
        var one = offer(List.of(new TradeCost("minecraft:emerald", "{}", 1),
                new TradeCost("minecraft:book", "{}", 1)), 1);
        assertAllowed(TradePolicy.check(order, one,
                Map.of("minecraft:emerald", 5, "minecraft:book", 2), progress(order, Map.of()), true, 1));

        var twoEmeralds = offer(List.of(new TradeCost("minecraft:emerald", "{}", 2),
                new TradeCost("minecraft:book", "{}", 1)), 1);
        assertFalse(TradePolicy.check(order, twoEmeralds,
                Map.of("minecraft:emerald", 5, "minecraft:book", 2), progress(order, Map.of()), true, 1).allowed());
        assertFalse(TradePolicy.check(order, one,
                Map.of("minecraft:emerald", 5, "minecraft:book", 1), progress(order, Map.of()), true, 1).allowed());
    }

    @Test void sameItemInputsWithDifferentPredicatesAreAggregated() {
        var budget = new TradeBudget(Map.of("minecraft:emerald", 10), Map.of("minecraft:emerald", 1),
                Map.of("minecraft:emerald", 5));
        var order = order(budget, 1, false);
        var costs = List.of(new TradeCost("minecraft:emerald", "{}", 3),
                new TradeCost("minecraft:emerald", "{\"custom\":1}", 3));
        var repeatedItemInputs = offer(costs, 1);

        assertFalse(TradePolicy.check(order, repeatedItemInputs, Map.of("minecraft:emerald", 6),
                progress(order, Map.of()), true, 1).allowed());
    }

    @Test void unitCostUsesWidenedMultiplicationAndLargeAggregateCostsDoNotWrap() {
        var budget = new TradeBudget(Map.of("minecraft:emerald", 2_000_000), Map.of(),
                Map.of("minecraft:emerald", 3_000_000));
        var order = order(budget, 2_000_000, true);
        var largeBatch = offer(new TradeCost("minecraft:emerald", "{}", TradeItem.MAX_COUNT), 1_000);
        assertAllowed(TradePolicy.check(order, largeBatch,
                Map.of("minecraft:emerald", 2_000_000), progress(order, 0), true, 1));

        var strict = new TradeOrder(UUID.randomUUID(), OWNER, SCOPE, TRADER, "offer", 1, Integer.MAX_VALUE,
                new TradeBudget(Map.of("minecraft:emerald", Integer.MAX_VALUE), Map.of(), Map.of()),
                Set.of(), false, false, 10);
        var costly = offer(new TradeCost("minecraft:emerald", "{}", TradeItem.MAX_COUNT), 1);
        assertFalse(TradePolicy.check(strict, costly,
                Map.of("minecraft:emerald", Integer.MAX_VALUE), progress(strict, 0), true, 1).allowed());
    }

    @Test void malformedBudgetsAndMissingInputCapsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TradeBudget(Map.of("minecraft:emerald", 0), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new TradeBudget(Map.of("minecraft:emerald", -1), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new TradeBudget(Map.of(), Map.of(), Map.of()));

        var order = order(new TradeBudget(Map.of("minecraft:book", 3), Map.of(), Map.of()), 1, false);
        var result = TradePolicy.check(order, offer(new TradeCost("minecraft:emerald", "{}", 1), 1),
                Map.of("minecraft:emerald", 4), progress(order, 0), true, 1);
        assertFalse(result.allowed());
    }

    @Test void expiredOfferAndOutputCapacityCannotAuthorizeAnotherAttempt() {
        var order = order(new TradeBudget(Map.of("minecraft:emerald", 10), Map.of(), Map.of()), 1, false);
        var offer = offer(new TradeCost("minecraft:emerald", "{}", 1), 1);
        assertFalse(TradePolicy.check(order, offer, Map.of("minecraft:emerald", 10), progress(order, 0), true, 11).allowed());
        assertFalse(TradePolicy.check(order, offer, Map.of("minecraft:emerald", 10), progress(order, 0), false, 1).allowed());
    }

    @Test void nonPartialPlanRejectsOvershootAcrossAllRequiredFills() {
        var order = new TradeOrder(UUID.randomUUID(), OWNER, SCOPE, TRADER, "offer", 1, 5,
                new TradeBudget(Map.of("minecraft:emerald", 10), Map.of(), Map.of()),
                Set.of(), false, false, 10);
        var offer = offer(new TradeCost("minecraft:emerald", "{}", 1), 3);

        assertFalse(TradePolicy.check(order, offer, Map.of("minecraft:emerald", 10),
                progress(order, 0), true, 1).allowed());
    }

    private static void assertAllowed(TradePolicy.Decision decision) {
        assertTrue(decision.allowed(), decision.reason());
        assertEquals(1, decision.repeats());
    }

    private static TradeOrder order(TradeBudget budget, int requested, boolean overshoot) {
        return new TradeOrder(UUID.randomUUID(), OWNER, SCOPE, TRADER, "offer", 1, requested, budget,
                Set.of(), overshoot, true, 10);
    }

    private static TradeOffer offer(TradeCost cost, int outputCount) {
        return offer(List.of(cost), outputCount);
    }

    private static TradeOffer offer(List<TradeCost> inputs, int outputCount) {
        var output = new TradeItem("minecraft:book", "{}", outputCount);
        return new TradeOffer("offer", TradeOffer.fingerprint(inputs, output, 0), inputs, inputs, output,
                0, 100, 0, TRADER, 1);
    }

    private static TradeProgress progress(TradeOrder order, int spent) {
        return progress(order, spent == 0 ? Map.of() : Map.of("minecraft:emerald", spent));
    }

    private static TradeProgress progress(TradeOrder order, Map<String, Integer> spent) {
        return new TradeProgress(order.orderId(), spent.isEmpty() ? State.AUTHORIZED : State.RUNNING,
                spent, List.of(), 0, "");
    }
}
