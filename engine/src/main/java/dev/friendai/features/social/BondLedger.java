package dev.friendai.features.social;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded relationship score built only from real deeds. It never gates a permission: it exists to choose a tone.
 * A gift counts only when the balance for that kind grows past its previous peak; the daily cap and the one-point
 * decay per elapsed game day keep the number from being farmed.
 */
public final class BondLedger {
    public static final int BASE = 0;
    public static final int ACQUAINTANCE = 10;
    public static final int FRIEND = 30;
    public static final int CLOSE = 60;
    public static final int DAILY_CAP = 6;

    public enum Tier {
        STRANGER,
        ACQUAINTANCE,
        FRIEND,
        CLOSE
    }

    private final Map<String, Integer> giftPeaks = new HashMap<>();
    private final ArrayDeque<String> reasons = new ArrayDeque<>();
    private int score = BASE;
    private int lastDay;
    private int awardedToday;

    public int score() {
        return score;
    }

    public Tier tier() {
        if (score >= CLOSE) return Tier.CLOSE;
        if (score >= FRIEND) return Tier.FRIEND;
        if (score >= ACQUAINTANCE) return Tier.ACQUAINTANCE;
        return Tier.STRANGER;
    }

    public List<String> reasons() {
        return List.copyOf(reasons);
    }

    /**
     * Every field as one immutable value, newest reason first, so a save can carry the whole ledger across a restart.
     */
    public BondState snapshot() {
        return new BondState(score, lastDay, awardedToday, reasons(), giftPeaks);
    }

    /**
     * Replaces every field with a snapshot; a null state restores {@link BondState#EMPTY}, the ledger a fresh
     * companion starts with. The daily counter and the gift peaks come back exactly as they were saved.
     */
    public void restore(BondState state) {
        BondState value = state == null ? BondState.EMPTY : state;
        score = value.points();
        lastDay = value.lastDay();
        awardedToday = value.awardedToday();
        reasons.clear();
        reasons.addAll(value.reasons());
        giftPeaks.clear();
        giftPeaks.putAll(value.peaks());
    }

    /** One point per elapsed game day toward the base. */
    public void advanceDay(int day) {
        if (day <= lastDay) {
            return;
        }
        score = Math.max(BASE, score - (day - lastDay));
        lastDay = day;
        awardedToday = 0;
    }

    public boolean gift(String kind, int amount, int day) {
        advanceDay(day);
        int previous = giftPeaks.getOrDefault(kind, 0);
        if (amount <= previous) {
            return false;
        }
        giftPeaks.put(kind, amount);
        return award(3, "gift: " + kind);
    }

    public boolean rescue(int day) {
        advanceDay(day);
        return award(5, "rescue");
    }

    public boolean sharedWork(int day) {
        advanceDay(day);
        return award(2, "shared work");
    }

    public boolean hurtByOwner(int day) {
        advanceDay(day);
        if (awardedToday + 2 > DAILY_CAP) {
            return false;
        }
        score = Math.max(BASE, score - 2);
        addReason("owner hit");
        return true;
    }

    private boolean award(int points, String reason) {
        if (awardedToday + points > DAILY_CAP) {
            return false;
        }
        score += points;
        awardedToday += points;
        addReason(reason);
        return true;
    }

    private void addReason(String reason) {
        reasons.addFirst(reason);
        while (reasons.size() > BondState.MAX_REASONS) {
            reasons.removeLast();
        }
    }
}
