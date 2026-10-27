package dev.friendai.trading;

import java.util.Map;
import java.util.HashMap;

/** Pure last-moment check for one vanilla transaction. Component payment matching is adapter-owned. */
public final class TradePolicy {
    private TradePolicy() {}

    public record Decision(boolean allowed, String reason, int repeats) {
        public Decision {
            if (reason == null || repeats < 0 || repeats > 1 || (allowed && repeats != 1)
                    || (!allowed && repeats != 0)) throw new IllegalArgumentException("invalid trade decision");
        }
    }

    public static Decision check(TradeOrder order, TradeOffer live, Map<String, Integer> available,
                                 TradeProgress progress, boolean outputFits, long nowTick) {
        if (order == null || live == null || available == null || progress == null || nowTick < 0
                || !order.orderId().equals(progress.orderId())) return deny("trade progress does not match order");
        if (nowTick > order.deadlineTick()) return deny("trade order expired");
        if (!order.offerRef().equals(live.ref())) return deny("live offer no longer matches order");
        if (live.exhausted()) return deny("offer is exhausted");
        if (!outputFits) return deny("output does not fit");

        long received = 0;
        for (var item : progress.received()) {
            if (item.itemId().equals(live.output().itemId())
                    && item.componentsJson().equals(live.output().componentsJson())) {
                received += item.count();
                if (received > Integer.MAX_VALUE) return deny("receipt count overflow");
            }
        }
        if (received >= order.desiredOutputCount()) return deny("requested output is already complete");
        long transactions = order.allowPartial() ? 1
                : divideRoundUp(order.desiredOutputCount() - received, live.output().count());
        long plannedOutput;
        try {
            plannedOutput = Math.addExact(received,
                    Math.multiplyExact((long) live.output().count(), transactions));
        } catch (ArithmeticException overflow) {
            return deny("trade output count overflow");
        }
        if (plannedOutput > order.desiredOutputCount() && !order.allowOvershoot()) {
            return deny("offer would exceed requested output");
        }

        Map<String, Long> inputCosts = new HashMap<>();
        try {
            for (var cost : live.effectiveInputs()) {
                inputCosts.merge(cost.itemId(), (long) cost.count(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return deny("trade cost overflow");
        }
        for (var input : inputCosts.entrySet()) {
            String itemId = input.getKey();
            long cost = input.getValue();
            Integer cap = order.budget().inputCaps().get(itemId);
            Integer amount = progress.spent().getOrDefault(itemId, 0);
            Integer inventory = available.get(itemId);
            Integer reserve = order.budget().reserves().getOrDefault(itemId, 0);
            if (cap == null) return deny("missing input cap for " + itemId);
            if (inventory == null || inventory < 0 || amount < 0) return deny("invalid inventory or progress");

            long totalCost;
            try {
                totalCost = Math.multiplyExact(cost, transactions);
                long cumulative = Math.addExact((long) amount, totalCost);
                if (cumulative > cap) return deny("input cap exceeded for " + itemId);
                if (Math.subtractExact((long) inventory, totalCost) < reserve) {
                    return deny("protected reserve would be spent for " + itemId);
                }
            } catch (ArithmeticException overflow) {
                return deny("trade cost overflow");
            }

            Integer maximumUnitCost = order.budget().maxUnitCosts().get(itemId);
            if (maximumUnitCost != null) {
                try {
                    long allowedCost = Math.multiplyExact((long) maximumUnitCost, live.output().count());
                    if (cost > allowedCost) return deny("unit price cap exceeded for " + itemId);
                } catch (ArithmeticException overflow) {
                    return deny("unit price overflow");
                }
            }
        }
        return new Decision(true, "", 1);
    }

    private static long divideRoundUp(long value, long divisor) {
        if (divisor <= 0) throw new IllegalArgumentException("invalid output count");
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static Decision deny(String reason) {
        return new Decision(false, reason, 0);
    }
}
