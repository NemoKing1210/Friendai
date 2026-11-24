package dev.friendai.agent.verify;

import dev.friendai.execution.TaskWorkResult;
import java.util.ArrayList;
import java.util.List;

/**
 * One item outcome a task actually verified, kept with the goal and plan it proves. A receipt is evidence for that
 * work, never a statement about the current world: it is offered only to the planner of the same goal or the same
 * plan, and only while it is younger than {@link #MAX_AGE_TICKS}.
 *
 * <p>A handover is history: the owner took the items, so the receipt stays true after the companion's own inventory
 * changes. Carried and picked-up counts describe stock at that moment, which may since have been used, so a stock
 * receipt is offered only while a live count still backs it and is re-rendered with what remains.
 */
public record VerifiedEvidence(
        Kind kind, String itemId, int verified, int requested, long goalId, long planId, long tick) {
    /** The session keeps at most this many receipts, newest first. */
    public static final int MAX_ITEMS = 8;

    /** Twenty minutes at twenty ticks: long enough for a repair, short enough that stale stock is not claimed. */
    public static final long MAX_AGE_TICKS = 24_000L;

    public VerifiedEvidence {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId must not be blank");
        if (requested <= 0) throw new IllegalArgumentException("requested must be positive: " + requested);
        if (verified < 0) throw new IllegalArgumentException("verified must not be negative: " + verified);
        if (tick < 0) throw new IllegalArgumentException("tick must not be negative: " + tick);
        itemId = itemId.strip();
    }

    /** How the task established the item, and whether that record can still be trusted as current stock. */
    public enum Kind {
        /** The owner took the items; the handover is history and needs no live count. */
        DELIVERED("handed_over"),
        /** The companion carried the items at that moment, which may since have been used. */
        CARRIED("carried"),
        /** The companion picked the items up at that moment, which may since have been used. */
        PICKED_UP("picked_up");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Kind of(TaskWorkResult.Evidence evidence) {
            return switch (evidence) {
                case HANDED_OVER -> DELIVERED;
                case CARRIED -> CARRIED;
                case PICKED_UP -> PICKED_UP;
            };
        }
    }

    /** The line the planner reads, for example {@code handed_over 4/4 minecraft:stone_pickaxe}. */
    public String text() {
        return render(verified);
    }

    private String render(int count) {
        return kind.label() + " " + count + "/" + requested + " " + itemId;
    }

    /** True when this receipt stands for stock the companion held, which a live count must still back. */
    public boolean needsLiveStock() {
        return kind != Kind.DELIVERED;
    }

    /** True when this receipt proves something for that goal or that plan; an unowned receipt proves nothing. */
    public boolean appliesTo(long requestedGoal, long requestedPlan) {
        return (requestedGoal > 0 && goalId == requestedGoal) || (requestedPlan > 0 && planId == requestedPlan);
    }

    public boolean freshAt(long now) {
        return now - tick <= MAX_AGE_TICKS;
    }

    /** The receipts one task's verified lines earn, tagged with the goal and plan they prove. */
    public static List<VerifiedEvidence> fromWorkResult(TaskWorkResult result, long goalId, long planId, long tick) {
        if (result == null) return List.of();
        List<VerifiedEvidence> receipts = new ArrayList<>();
        for (TaskWorkResult.Line line : result.lines()) {
            if (line.verified() > 0) {
                receipts.add(new VerifiedEvidence(
                        Kind.of(line.evidence()),
                        line.itemId(),
                        line.verified(),
                        line.requested(),
                        goalId,
                        planId,
                        tick));
            }
        }
        return receipts;
    }

    /** A live view of what the companion carries now, which decides whether a stock receipt still holds. */
    @FunctionalInterface
    public interface LiveStock {
        /** No live view: every stock receipt is unproven and dropped rather than trusted. */
        LiveStock NONE = itemId -> -1;

        /** Items of that id or tag the companion carries now, or {@code -1} when it cannot be seen. */
        int count(String itemId);
    }

    /**
     * The texts to offer one planner objective, newest first and bounded. An objective that names neither a goal nor
     * a plan inherits nothing: the work that produced these receipts is not the work being planned.
     */
    public static List<String> forObjective(List<VerifiedEvidence> receipts, long goalId, long planId, long now) {
        return forObjective(receipts, goalId, planId, now, LiveStock.NONE);
    }

    /**
     * The texts to offer one planner objective, with stock receipts rechecked against what the companion carries now.
     * A stock receipt whose items are gone is dropped; a partially used one is offered with the count that remains.
     */
    public static List<String> forObjective(
            List<VerifiedEvidence> receipts, long goalId, long planId, long now, LiveStock stock) {
        List<String> texts = new ArrayList<>();
        if (receipts == null || (goalId <= 0 && planId <= 0)) {
            return texts;
        }
        LiveStock live = stock == null ? LiveStock.NONE : stock;
        for (VerifiedEvidence receipt : receipts) {
            if (texts.size() >= MAX_ITEMS) {
                break;
            }
            if (receipt == null || !receipt.appliesTo(goalId, planId) || !receipt.freshAt(now)) {
                continue;
            }
            if (!receipt.needsLiveStock()) {
                texts.add(receipt.text());
                continue;
            }
            int held = live.count(receipt.itemId());
            if (held <= 0) {
                continue;
            }
            texts.add(receipt.render(Math.min(held, receipt.verified())));
        }
        return texts;
    }
}
