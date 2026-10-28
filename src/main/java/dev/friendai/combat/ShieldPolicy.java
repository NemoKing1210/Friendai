package dev.friendai.combat;

/**
 * When to hold the shield up and when to lower it. Pure: the adapter reports what it sees and executes the
 * result.
 *
 * <p>A raised shield is for a threat that has not landed yet: an arrow already in flight always wins, a visible
 * shooter's draw raises the shield before the arrow exists, and a target in melee reach lowers it so the
 * companion swings instead of fighting behind it.
 */
public final class ShieldPolicy {
    private ShieldPolicy() {}

    public enum Need {
        RAISE,
        KEEP,
        /** No shield value this tick; the caller may swing, dodge or move. */
        LOWER
    }

    /**
     * @param shieldAvailable a shield is in the offhand or anywhere in the bag
     * @param raised the shield is already in use this tick
     * @param inFlight a projectile is on a collision course
     * @param windup a visible shooter is drawing at the companion or the owner
     * @param frontal the threat arrives from the half-space the companion faces
     * @param inReach the melee target is close enough to swing at
     */
    public static Need decide(
            boolean shieldAvailable,
            boolean raised,
            boolean inFlight,
            boolean windup,
            boolean frontal,
            boolean inReach) {
        if (!shieldAvailable) {
            return Need.LOWER;
        }
        if (inFlight && frontal) {
            return raised ? Need.KEEP : Need.RAISE;
        }
        if (windup && frontal && !inReach) {
            return raised ? Need.KEEP : Need.RAISE;
        }
        return Need.LOWER;
    }
}
