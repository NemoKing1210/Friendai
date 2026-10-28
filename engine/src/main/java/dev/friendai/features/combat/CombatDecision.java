package dev.friendai.features.combat;

import java.util.Objects;

/** The one primary combat action selected for a tick, with a stable reason for status and tests. */
public record CombatDecision(Action action, Reason reason) {
    public CombatDecision {
        action = Objects.requireNonNull(action, "action");
        reason = Objects.requireNonNull(reason, "reason");
        if (action == Action.NONE && reason != Reason.NONE) {
            throw new IllegalArgumentException("a NONE action must carry a NONE reason");
        }
        if (action != Action.NONE && reason == Reason.NONE) {
            throw new IllegalArgumentException("an action must carry a reason");
        }
    }

    public enum Action {
        NONE,
        /** Swing at the current target. */
        ATTACK,
        /** Close the distance to a melee target. */
        MELEE_APPROACH,
        /** Keep or create the style's preferred distance. */
        KEEP_RANGE,
        /** Raise a shield against the current threat direction. */
        SHIELD,
        /** Step sideways off the predicted projectile path. */
        DODGE,
        /** Move behind cover from a projectile. */
        SEEK_COVER,
        /** Disengage to survive. */
        RETREAT,
        /** Eat owned food to recover. */
        EAT,
        /** Draw and release a bow at a clear lane. */
        FIRE_BOW,
        /** Throw a splash potion. */
        THROW_POTION,
        /** End an engagement that is no longer worth continuing. */
        DISENGAGE,
        /** Stay out of the fight while avoid-combat is set. */
        AVOID
    }

    public enum Reason {
        NONE,
        IMMINENT_DANGER,
        BLOCK_PROJECTILE,
        BLOCK_MELEE,
        EVADE_PROJECTILE,
        EVADE_MELEE,
        SURVIVE,
        RECOVER,
        DEFEND_SELF,
        DEFEND_OWNER,
        GUARD,
        ATTACK,
        POSITION,
        OUT_OF_PURSUIT,
        STYLE_RANGE,
        AVOID_COMBAT,
        NO_WEAPON,
        NO_AMMUNITION,
        UNSAFE_LANE
    }

    public static CombatDecision none() {
        return new CombatDecision(Action.NONE, Reason.NONE);
    }

    /** True when the decision causes damage or effect delivery. */
    public boolean attacks() {
        return action == Action.ATTACK || action == Action.FIRE_BOW || action == Action.THROW_POTION;
    }

    /** True when the decision moves the companion or raises a shield. */
    public boolean moves() {
        return action == Action.MELEE_APPROACH
                || action == Action.KEEP_RANGE
                || action == Action.DODGE
                || action == Action.SEEK_COVER
                || action == Action.RETREAT
                || action == Action.SHIELD;
    }
}
