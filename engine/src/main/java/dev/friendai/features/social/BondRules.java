package dev.friendai.features.social;

/**
 * The world facts a {@link BondLedger} must not know about: when a deed happened and what state the fight was in.
 * Every method is a plain question with a plain answer, so the Minecraft adapter only has to gather the numbers and
 * the ledger only has to do the arithmetic.
 */
public final class BondRules {
    /** An owner hit counts only this far after the previous counted one: 30 s at 20 ticks per second. */
    public static final long OWNER_HIT_COOLDOWN_TICKS = 600;

    /** A rescue counts only while the rescued player hit the owner this many ticks ago. */
    public static final long RESCUE_WINDOW_TICKS = 200;

    private BondRules() {}

    /** A hit by the owner is a deed only outside a monster fight and never twice inside the cooldown. */
    public static boolean ownerHitCounts(boolean ownerHit, boolean inFightWithMonster, long ticksSinceLastHit) {
        return ownerHit && !inFightWithMonster && ticksSinceLastHit >= OWNER_HIT_COOLDOWN_TICKS;
    }

    /**
     * The victim must have hit the owner recently for the kill to count as a rescue; a negative age means no such
     * hit was ever recorded.
     */
    public static boolean rescueCounts(long ticksSinceVictimHitOwner) {
        return ticksSinceVictimHitOwner >= 0 && ticksSinceVictimHitOwner <= RESCUE_WINDOW_TICKS;
    }

    /**
     * A closed inventory is worth only the growth above the previous peak of its kind, so putting the same item back
     * after taking it out awards nothing.
     */
    public static int giftAward(int previousPeak, int closedInventoryValue) {
        return Math.max(0, Math.max(0, closedInventoryValue) - Math.max(0, previousPeak));
    }

    /** Whether the game day moved forward; the ledger never moves back on a clock that restarts lower. */
    public static boolean dayAdvanced(long currentDay, int recordedDay) {
        return currentDay > recordedDay;
    }

    /**
     * The ledger as the given day sees it: a changed day applies the one-point-per-day decay toward
     * {@link BondLedger#BASE} and resets the daily award counter, and the same day changes nothing. A null ledger
     * becomes a fresh one, so loading always yields a usable ledger.
     */
    public static BondLedger forDay(BondLedger ledger, long currentDay) {
        BondLedger target = ledger == null ? new BondLedger() : ledger;
        target.advanceDay(clampDay(currentDay));
        return target;
    }

    /** {@link BondLedger} counts days as ints; a clock beyond that range is clamped instead of wrapping. */
    private static int clampDay(long day) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, day));
    }
}
