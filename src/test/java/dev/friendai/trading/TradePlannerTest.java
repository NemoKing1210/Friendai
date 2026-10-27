package dev.friendai.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TradePlannerTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String EMERALD = "minecraft:emerald";
    private static final String DIAMOND = "minecraft:diamond";
    private static final String BREAD = "minecraft:bread";

    @Test
    void plans_exact_affordable_fills_with_both_effective_inputs() {
        TradeOffer offer = offer("bread", List.of(cost(EMERALD, 2), cost(DIAMOND, 1)),
                List.of(cost(EMERALD, 2), cost(DIAMOND, 1)), item(BREAD, 3), 0, 3, 10);
        TraderRecord trader = trader(1, TraderRecord.Status.VERIFIED, 10, offer);

        Map<String, Integer> inventory = new HashMap<>(Map.of(EMERALD, 5, DIAMOND, 2));
        TradePlan plan = TradePlanner.plan(buyBread(), 6, List.of(trader), inventory,
                budget(Map.of(EMERALD, 4, DIAMOND, 2), Map.of(EMERALD, 1), Map.of()), 10);

        assertEquals(1, plan.fills().size());
        TradePlan.Fill fill = plan.fills().getFirst();
        assertEquals(trader.id(), fill.trader());
        assertEquals(offer.ref(), fill.offerRef());
        assertEquals(trader.revision(), fill.revision());
        assertEquals(2, fill.repeats());
        assertEquals(List.of(cost(EMERALD, 2), cost(DIAMOND, 1)), fill.inputs());
        assertEquals(item(BREAD, 3), fill.output());
        assertTrue(plan.missing().isEmpty());
        assertFalse(plan.conditional());
        assertTrue(plan.needsInspection().isEmpty());
        assertEquals(Map.of(EMERALD, 5, DIAMOND, 2), inventory, "planning must not spend inventory");
    }

    @Test
    void caps_and_reserves_are_applied_independently_to_both_inputs() {
        TradeOffer offer = offer("two-input", List.of(cost(EMERALD, 2), cost(DIAMOND, 1)),
                List.of(cost(EMERALD, 2), cost(DIAMOND, 1)), item(BREAD, 1), 0, 20, 10);
        TraderRecord trader = trader(2, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 5, List.of(trader),
                Map.of(EMERALD, 10, DIAMOND, 10), budget(Map.of(EMERALD, 4, DIAMOND, 1),
                        Map.of(EMERALD, 2, DIAMOND, 1), Map.of()), 10);

        assertEquals(1, plan.fills().getFirst().repeats(),
                "the smaller diamond cap must limit fills even when emeralds remain");
        assertTrue(plan.missing().isEmpty(), "budget-limited output is not an inventory shortage");
    }

    @Test
    void rounds_down_to_whole_output_batches_when_overshoot_is_denied() {
        TradeOffer offer = offer("batch-three", List.of(cost(EMERALD, 1)),
                List.of(cost(EMERALD, 1)), item(BREAD, 3), 0, 20, 10);
        TraderRecord trader = trader(3, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 5, List.of(trader), Map.of(EMERALD, 10),
                budget(Map.of(EMERALD, 10), Map.of(), Map.of()), 10);

        assertEquals(1, plan.fills().getFirst().repeats());
        assertEquals(3, plan.fills().getFirst().repeats() * plan.fills().getFirst().output().count());
    }

    @Test
    void rounds_up_to_a_complete_batch_only_when_overshoot_is_allowed() {
        TradeOffer offer = offer("batch-three", List.of(cost(EMERALD, 1)),
                List.of(cost(EMERALD, 1)), item(BREAD, 3), 0, 20, 10);
        TraderRecord trader = trader(4, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 5, List.of(trader), Map.of(EMERALD, 10),
                budget(Map.of(EMERALD, 10), Map.of(), Map.of()), 10, true);

        assertEquals(2, plan.fills().getFirst().repeats());
    }

    @Test
    void exhausted_offer_is_not_planned_and_requests_inspection() {
        TradeOffer offer = offer("sold-out", List.of(cost(EMERALD, 1)),
                List.of(cost(EMERALD, 1)), item(BREAD, 3), 4, 4, 10);
        TraderRecord trader = trader(5, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 3, List.of(trader), Map.of(EMERALD, 10),
                budget(Map.of(EMERALD, 10), Map.of(), Map.of()), 10);

        assertTrue(plan.fills().isEmpty());
        assertEquals(List.of(trader.id()), plan.needsInspection());
    }

    @Test
    void reports_missing_payment_resources_after_affordable_fills() {
        TradeOffer offer = offer("needs-emeralds", List.of(cost(EMERALD, 2), cost(DIAMOND, 1)),
                List.of(cost(EMERALD, 2), cost(DIAMOND, 1)), item(BREAD, 3), 0, 20, 10);
        TraderRecord trader = trader(6, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 6, List.of(trader),
                Map.of(EMERALD, 3, DIAMOND, 2), budget(Map.of(EMERALD, 10, DIAMOND, 10),
                        Map.of(EMERALD, 1), Map.of()), 10);

        assertEquals(1, plan.fills().getFirst().repeats());
        assertEquals(Map.of(EMERALD, 2), plan.missing());
    }

    @Test
    void rejects_prices_over_unit_caps_without_treating_them_as_missing_inventory() {
        TradeOffer offer = offer("too-expensive", List.of(cost(EMERALD, 4)),
                List.of(cost(EMERALD, 4)), item(BREAD, 3), 0, 20, 10);
        TraderRecord trader = trader(7, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 3, List.of(trader), Map.of(EMERALD, 10),
                budget(Map.of(EMERALD, 10), Map.of(), Map.of(EMERALD, 1)), 10);

        assertTrue(plan.fills().isEmpty());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void stale_matching_quotes_are_conditional_and_require_inspection() {
        TradeOffer offer = offer("old-quote", List.of(cost(EMERALD, 1)),
                List.of(cost(EMERALD, 1)), item(BREAD, 3), 0, 20, 10);
        TraderRecord trader = trader(8, TraderRecord.Status.BUSY, 20, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 3, List.of(trader), Map.of(EMERALD, 3),
                budget(Map.of(EMERALD, 3), Map.of(), Map.of()), 20);

        assertEquals(1, plan.fills().size());
        assertTrue(plan.conditional());
        assertEquals(List.of(trader.id()), plan.needsInspection());
    }

    @Test
    void fresh_quote_for_another_pricing_actor_is_conditional_and_needs_inspection() {
        TradeOffer offer = offer("other-actor", List.of(cost(EMERALD, 1)),
                List.of(cost(EMERALD, 1)), item(BREAD, 3), 0, 20, 10);
        TraderRecord trader = trader(11, TraderRecord.Status.VERIFIED, 10, offer);

        TradePlan plan = TradePlanner.plan(buyBread(), 3, List.of(trader), Map.of(EMERALD, 3),
                budget(Map.of(EMERALD, 3), Map.of(), Map.of()), 10, false, OTHER_ACTOR);

        assertEquals(1, plan.fills().size(), "planning remains informational even for a stale actor quote");
        assertTrue(plan.conditional());
        assertEquals(List.of(trader.id()), plan.needsInspection());
    }

    @Test
    void sell_query_counts_requested_input_batches_and_still_budgets_the_second_input() {
        TradeOffer offer = offer("sell-gold", List.of(cost("minecraft:gold_ingot", 2), cost(EMERALD, 1)),
                List.of(cost("minecraft:gold_ingot", 2), cost(EMERALD, 1)), item("minecraft:gold_nugget", 3), 0, 20, 10);
        TraderRecord trader = trader(9, TraderRecord.Status.VERIFIED, 10, offer);
        TradeQuery query = new TradeQuery(TradeQuery.Direction.SELL, "minecraft:gold_ingot", "{}", 40);

        TradePlan plan = TradePlanner.plan(query, 4, List.of(trader),
                Map.of("minecraft:gold_ingot", 4, EMERALD, 2),
                budget(Map.of("minecraft:gold_ingot", 4, EMERALD, 2), Map.of(), Map.of()), 10);

        assertEquals(2, plan.fills().getFirst().repeats());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void sell_query_counts_only_the_matching_component_predicate_input() {
        String predicate = "{\"minecraft:custom_name\":\"target\"}";
        TradeOffer offer = offer("sell-component", List.of(cost("minecraft:gold_ingot", 2),
                        cost("minecraft:gold_ingot", predicate, 1)),
                List.of(cost("minecraft:gold_ingot", 2), cost("minecraft:gold_ingot", predicate, 1)),
                item("minecraft:gold_nugget", 3), 0, 20, 10);
        TraderRecord trader = trader(10, TraderRecord.Status.VERIFIED, 10, offer);
        TradeQuery query = new TradeQuery(TradeQuery.Direction.SELL, "minecraft:gold_ingot", predicate, 40);

        TradePlan plan = TradePlanner.plan(query, 2, List.of(trader),
                Map.of("minecraft:gold_ingot", 6),
                budget(Map.of("minecraft:gold_ingot", 6), Map.of(), Map.of()), 10);

        assertEquals(1, plan.fills().size());
        assertEquals(2, plan.fills().getFirst().repeats(),
                "the generic same-item input does not satisfy a component-filtered SELL query");
    }

    private static TradeQuery buyBread() {
        return new TradeQuery(TradeQuery.Direction.BUY, BREAD, "{}", 40);
    }

    private static TradeBudget budget(Map<String, Integer> caps, Map<String, Integer> reserves,
                                      Map<String, Integer> unitCaps) {
        return new TradeBudget(caps, reserves, unitCaps);
    }

    private static TradeCost cost(String id, int count) {
        return new TradeCost(id, "{}", count);
    }

    private static TradeCost cost(String id, String predicate, int count) {
        return new TradeCost(id, predicate, count);
    }

    private static TradeItem item(String id, int count) {
        return new TradeItem(id, "{}", count);
    }

    private static TradeOffer offer(String ref, List<TradeCost> base, List<TradeCost> effective,
                                    TradeItem output, int uses, int maxUses, long observedTick) {
        String fingerprint = String.format("%064x", ref.hashCode() & 0xffffffffL);
        return new TradeOffer(ref, fingerprint, base, effective, output, uses, maxUses,
                1, ACTOR, observedTick);
    }

    private static TraderRecord trader(int suffix, TraderRecord.Status status, long tick, TradeOffer... offers) {
        UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
        return new TraderRecord(id, "minecraft:villager", "minecraft:overworld", suffix, 64, 0,
                "villager", "", 2, 0, tick, tick, 1, status, List.of(offers));
    }
}
