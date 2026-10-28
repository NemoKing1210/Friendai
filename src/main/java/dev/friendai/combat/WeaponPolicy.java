package dev.friendai.combat;

/**
 * Tactical weapon scoring and distance bands. Pure: candidates are plain facts gathered from the inventory
 * and the target; a style can only reorder usable options, never invent equipment or bypass permissions.
 */
public final class WeaponPolicy {
    /** Re-enter melee at this much inside reach. */
    public static final double BAND_ENTER_REACH = 0.5;
    /** Leave melee only after this much beyond reach. */
    public static final double BAND_LEAVE_REACH = 1.0;
    /** Below this durability fraction a weapon is not considered usable. */
    public static final double MIN_DURABILITY = 0.1;
    /** Useful bow distance; close enough to hit, far enough to react. */
    public static final double BOW_DISTANCE = 8.0;
    /** A bow already in hand stays until the target closes this far inside the style's start distance. */
    public static final double BOW_HYSTERESIS = 1.5;

    private static final double AXE_PREFERENCE = 0.97;

    private WeaponPolicy() {}

    public enum Intent {
        /** Attack now. */
        STRIKE,
        /** Move closer. */
        CLOSE,
        /** Move away or hold the current band. */
        KEEP_RANGE,
        /** Shield or hold while waiting for a window. */
        BLOCK,
        /** No usable option; fall back to survival behaviour. */
        FALLBACK
    }

    /** What is known about one candidate weapon (or the bow, when {@code bow} is set). */
    public record Facts(
            double dps,
            double reach,
            double durabilityFraction,
            boolean axe,
            boolean sword,
            boolean bow,
            double distance,
            boolean safe,
            boolean targetFlying) {
        public static Facts melee(
                double dps,
                double reach,
                double durability,
                boolean axe,
                boolean sword,
                double distance,
                boolean safe) {
            return new Facts(dps, reach, durability, axe, sword, false, distance, safe, false);
        }

        public static Facts bow(double durability, double distance, boolean safe, boolean flying) {
            return new Facts(0.0, 0.0, durability, false, false, true, distance, safe, flying);
        }
    }

    /** The style's ordering of one candidate. A non-positive utility means "not worth selecting". */
    public record Score(double utility, double preferredDistance, Intent intent, boolean usable) {}

    public static Score score(Facts facts, FightingStyle style) {
        if (facts.durabilityFraction() < MIN_DURABILITY) {
            return new Score(0.0, 0.0, Intent.FALLBACK, false);
        }
        if (facts.bow()) {
            return scoreBow(facts, style);
        }
        double utility = Math.max(0.0, facts.dps()) * (facts.safe() ? 1.0 : 0.35);
        if (facts.axe() && !facts.sword()) {
            utility *= AXE_PREFERENCE;
        }
        return switch (style) {
            case RANGED ->
                facts.bow() || facts.dps() >= 6.0
                        ? new Score(utility * 0.6, BOW_DISTANCE, Intent.KEEP_RANGE, true)
                        : new Score(
                                utility * 0.5,
                                facts.reach(),
                                facts.distance() <= facts.reach() ? Intent.STRIKE : Intent.CLOSE,
                                utility > 0);
            case CAUTIOUS -> {
                double preferred = Math.max(facts.reach() + 1.5, 3.0);
                Intent intent;
                if (!facts.safe()) {
                    intent = Intent.BLOCK;
                } else if (facts.distance() < preferred) {
                    intent = Intent.KEEP_RANGE;
                } else if (facts.distance() <= facts.reach()) {
                    intent = Intent.STRIKE;
                } else {
                    intent = Intent.CLOSE;
                }
                yield new Score(utility * 0.8, preferred, intent, utility > 0);
            }
            case MELEE -> {
                Intent intent = facts.distance() <= facts.reach() ? Intent.STRIKE : Intent.CLOSE;
                yield new Score(utility * 1.1, facts.reach() - 0.1, intent, utility > 0);
            }
            case BALANCED -> {
                Intent intent = facts.distance() <= facts.reach() ? Intent.STRIKE : Intent.CLOSE;
                yield new Score(utility, facts.reach() - 0.2, intent, utility > 0);
            }
        };
    }

    private static Score scoreBow(Facts facts, FightingStyle style) {
        double utility = 6.0 * (facts.durabilityFraction() >= MIN_DURABILITY ? 1.0 : 0.0) + (facts.safe() ? 2.0 : 0.0);
        return switch (style) {
            case RANGED -> new Score(utility * 1.5, BOW_DISTANCE, Intent.KEEP_RANGE, true);
            case CAUTIOUS -> new Score(utility, BOW_DISTANCE, Intent.KEEP_RANGE, true);
            case BALANCED ->
                new Score(
                        facts.targetFlying() || facts.distance() > 6.0 ? utility : utility * 0.5,
                        BOW_DISTANCE,
                        Intent.KEEP_RANGE,
                        true);
            case MELEE -> new Score(utility * 0.4, BOW_DISTANCE, Intent.KEEP_RANGE, true);
        };
    }

    /**
     * Whether the style wants the bow at this distance. Starting a bow needs the style's full distance; a bow
     * already in hand stays until the target closes {@link #BOW_HYSTERESIS} blocks inside it, so a target hovering
     * at the boundary does not make the weapon flicker. A flying target and (for balanced) an unreachable melee
     * target always want the bow.
     */
    public static boolean bowWanted(
            FightingStyle style, double distance, boolean targetFlying, boolean unreachable, boolean bowHeld) {
        if (targetFlying) {
            return true;
        }
        double threshold = switch (style) {
            case RANGED -> 4.0;
            case MELEE -> 10.0;
            case CAUTIOUS, BALANCED -> 6.0;
        };
        double leave = bowHeld ? BOW_HYSTERESIS : 0.0;
        return distance > threshold - leave || (style == FightingStyle.BALANCED && unreachable);
    }

    /**
     * Distance band with hysteresis: it takes more distance to leave melee reach than to enter it, so a target
     * hovering at the edge does not flip the movement every tick.
     */
    public static Band band(double distance, double reach, Band current) {
        double enter = reach + BAND_ENTER_REACH;
        double leave = reach + BAND_LEAVE_REACH;
        return switch (current) {
            case IN_REACH -> distance > leave ? Band.OUT_OF_REACH : Band.IN_REACH;
            case OUT_OF_REACH -> distance < enter ? Band.IN_REACH : Band.OUT_OF_REACH;
            case UNKNOWN -> distance <= reach ? Band.IN_REACH : Band.OUT_OF_REACH;
        };
    }

    public enum Band {
        UNKNOWN,
        IN_REACH,
        OUT_OF_REACH
    }
}
