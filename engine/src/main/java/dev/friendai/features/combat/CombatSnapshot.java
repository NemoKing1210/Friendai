package dev.friendai.features.combat;

import java.util.Objects;

/**
 * One bounded, immutable view of the fight for a single decision. The server adapter gathers it on the server
 * thread; the policy performs no world access, path search or side effect.
 */
public record CombatSnapshot(Self self, Target target, Options options) {
    public CombatSnapshot {
        self = Objects.requireNonNull(self, "self");
        target = Objects.requireNonNull(target, "target");
        options = Objects.requireNonNull(options, "options");
    }

    /** The companion's own state and immediate survival options. */
    public record Self(
            double healthFraction,
            boolean dominantDanger,
            boolean canEat,
            boolean retreatPossible,
            boolean shieldAvailable,
            boolean shieldRaised,
            boolean frontalThreat,
            boolean projectileThreat,
            boolean safeDodge,
            boolean safeCover,
            boolean bowAvailable,
            boolean firingLaneClear,
            boolean allyInLane,
            boolean potionAvailable,
            boolean potionSafe,
            boolean potionCooldownReady,
            int potionBudget,
            /** A visible shooter is drawing at the companion or the owner; the arrow may not exist yet. */
            boolean rangedWindup) {
        /** No shield, no bow, no potion and no immediate danger. */
        public static Self bare(double healthFraction) {
            return new Self(
                    healthFraction,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0,
                    false);
        }
    }

    /**
     * The selected target. {@code attackable} is the adapter's {@code Threats}/PvP result; {@code aggressedSelf}
     * and {@code aggressedOwner} describe who the target actually attacked.
     */
    public record Target(
            boolean present,
            boolean visible,
            boolean attackable,
            boolean aggressedSelf,
            boolean aggressedOwner,
            boolean hostileMob,
            double distance,
            double anchorDistance,
            boolean flying,
            boolean closing) {
        public static Target none() {
            return new Target(false, false, false, false, false, false, Double.MAX_VALUE, 0.0, false, false);
        }
    }

    /** Movement and attack readiness for this tick. */
    public record Options(boolean canMelee, double meleeDps, boolean inBand) {
        public static Options none() {
            return new Options(false, 0.0, false);
        }
    }

    public static CombatSnapshot idle() {
        return new CombatSnapshot(Self.bare(1.0), Target.none(), Options.none());
    }
}
