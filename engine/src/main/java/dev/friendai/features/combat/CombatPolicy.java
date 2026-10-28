package dev.friendai.features.combat;

/**
 * Deterministic combat arbitration. Pure: no world access, randomness or side effects. The server adapter turns
 * one {@link CombatSnapshot} into at most one {@link CombatDecision} and executes it.
 *
 * <p>Priority follows the design: imminent danger, immediate block/dodge, emergency recovery, permitted attack,
 * positioning, then disengagement.
 */
public final class CombatPolicy {
    /** The old validated low-health threshold, kept in one place. */
    public static final double CRITICAL_HEALTH = 0.25;
    /** How much health conservative retreat gives up before the worry threshold. */
    public static final double CONSERVATIVE_RETREAT_HEALTH = 0.5;
    /** A lead estimate is never projected further than this many ticks. */
    public static final int MAX_LEAD_TICKS = 20;
    /** A projectile is only forecast inside this short horizon. */
    public static final int PROJECTILE_HORIZON_TICKS = 20;

    private CombatPolicy() {}

    public static CombatDecision decide(CombatSnapshot snapshot, CombatSettings settings) {
        CombatSnapshot.Self self = snapshot.self();
        CombatSnapshot.Target target = snapshot.target();
        CombatSnapshot.Options options = snapshot.options();

        // 1. Imminent lethal or environmental danger.
        if (self.dominantDanger()) {
            if (self.retreatPossible()) {
                return new CombatDecision(CombatDecision.Action.RETREAT, CombatDecision.Reason.IMMINENT_DANGER);
            }
            if (self.shieldAvailable()) {
                return new CombatDecision(CombatDecision.Action.SHIELD, CombatDecision.Reason.IMMINENT_DANGER);
            }
            if (self.canEat()) {
                return new CombatDecision(CombatDecision.Action.EAT, CombatDecision.Reason.RECOVER);
            }
            return new CombatDecision(CombatDecision.Action.AVOID, CombatDecision.Reason.SURVIVE);
        }

        // 2. Immediate block, dodge or cover. Protective eligibility is irrelevant to physical danger.
        if (self.projectileThreat() || self.rangedWindup()) {
            ShieldPolicy.Need shield = ShieldPolicy.decide(
                    self.shieldAvailable(),
                    self.shieldRaised(),
                    self.projectileThreat(),
                    self.rangedWindup(),
                    self.frontalThreat(),
                    options.inBand());
            if (shield != ShieldPolicy.Need.LOWER) {
                return new CombatDecision(CombatDecision.Action.SHIELD, CombatDecision.Reason.BLOCK_PROJECTILE);
            }
            if (self.projectileThreat()) {
                if (self.safeDodge()) {
                    return new CombatDecision(CombatDecision.Action.DODGE, CombatDecision.Reason.EVADE_PROJECTILE);
                }
                if (self.safeCover()) {
                    return new CombatDecision(CombatDecision.Action.SEEK_COVER, CombatDecision.Reason.EVADE_PROJECTILE);
                }
                if (self.retreatPossible()) {
                    return new CombatDecision(CombatDecision.Action.RETREAT, CombatDecision.Reason.EVADE_PROJECTILE);
                }
                return new CombatDecision(CombatDecision.Action.AVOID, CombatDecision.Reason.SURVIVE);
            }
        }

        // 3. Emergency recovery.
        if (self.healthFraction() <= CRITICAL_HEALTH) {
            if (self.canEat()) {
                return new CombatDecision(CombatDecision.Action.EAT, CombatDecision.Reason.SURVIVE);
            }
            if (self.retreatPossible()) {
                return new CombatDecision(CombatDecision.Action.RETREAT, CombatDecision.Reason.SURVIVE);
            }
        }
        if (settings.retreatPreference() == RetreatPreference.CONSERVATIVE
                && self.healthFraction() <= CONSERVATIVE_RETREAT_HEALTH
                && self.retreatPossible()
                && target.present()) {
            return new CombatDecision(CombatDecision.Action.RETREAT, CombatDecision.Reason.SURVIVE);
        }

        // Avoid-combat never initiates damage; it may still evade.
        if (settings.protection() == ProtectionPolicy.AVOID_COMBAT) {
            if (target.present() && target.distance() <= 8.0) {
                if (self.frontalThreat() && self.shieldAvailable()) {
                    return new CombatDecision(CombatDecision.Action.SHIELD, CombatDecision.Reason.AVOID_COMBAT);
                }
                if (self.safeDodge()) {
                    return new CombatDecision(CombatDecision.Action.DODGE, CombatDecision.Reason.AVOID_COMBAT);
                }
                if (self.retreatPossible()) {
                    return new CombatDecision(CombatDecision.Action.RETREAT, CombatDecision.Reason.AVOID_COMBAT);
                }
            }
            return new CombatDecision(CombatDecision.Action.AVOID, CombatDecision.Reason.AVOID_COMBAT);
        }

        boolean selfDefense = target.aggressedSelf();
        boolean ownerDefense = target.aggressedOwner() && settings.protection().defendsOwner();
        boolean guard = target.hostileMob() && settings.protection().guardsArea();
        boolean mayAttack =
                target.present() && target.visible() && target.attackable() && (selfDefense || ownerDefense || guard);
        if (!mayAttack) {
            return CombatDecision.none();
        }
        if (target.anchorDistance() > settings.pursuitLimitBlocks()) {
            return new CombatDecision(CombatDecision.Action.DISENGAGE, CombatDecision.Reason.OUT_OF_PURSUIT);
        }
        CombatDecision.Reason reason = selfDefense
                ? CombatDecision.Reason.DEFEND_SELF
                : ownerDefense ? CombatDecision.Reason.DEFEND_OWNER : CombatDecision.Reason.GUARD;

        return switch (settings.style()) {
            case RANGED -> ranged(snapshot, reason);
            case CAUTIOUS -> cautious(snapshot, reason);
            case MELEE -> melee(snapshot, reason);
            case BALANCED -> balanced(snapshot, reason);
        };
    }

    private static CombatDecision ranged(CombatSnapshot snapshot, CombatDecision.Reason reason) {
        CombatSnapshot.Self self = snapshot.self();
        CombatSnapshot.Target target = snapshot.target();
        CombatSnapshot.Options options = snapshot.options();
        if (self.bowAvailable() && target.closing() && target.distance() < 4.0) {
            return new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.STYLE_RANGE);
        }
        if (self.bowAvailable() && releaseAllowed(snapshot)) {
            return new CombatDecision(CombatDecision.Action.FIRE_BOW, reason);
        }
        if (options.canMelee()) {
            return new CombatDecision(CombatDecision.Action.ATTACK, reason);
        }
        if (self.bowAvailable()) {
            return new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.UNSAFE_LANE);
        }
        return new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reason);
    }

    private static CombatDecision cautious(CombatSnapshot snapshot, CombatDecision.Reason reason) {
        CombatSnapshot.Self self = snapshot.self();
        CombatSnapshot.Options options = snapshot.options();
        if (!options.inBand()) {
            if (self.shieldAvailable()) {
                return new CombatDecision(CombatDecision.Action.SHIELD, CombatDecision.Reason.POSITION);
            }
            return new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.POSITION);
        }
        if (options.canMelee()) {
            return new CombatDecision(CombatDecision.Action.ATTACK, reason);
        }
        return new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.POSITION);
    }

    private static CombatDecision melee(CombatSnapshot snapshot, CombatDecision.Reason reason) {
        CombatSnapshot.Options options = snapshot.options();
        if (!options.inBand()) {
            return new CombatDecision(CombatDecision.Action.MELEE_APPROACH, CombatDecision.Reason.POSITION);
        }
        if (options.canMelee()) {
            return new CombatDecision(CombatDecision.Action.ATTACK, reason);
        }
        return new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reason);
    }

    private static CombatDecision balanced(CombatSnapshot snapshot, CombatDecision.Reason reason) {
        CombatSnapshot.Self self = snapshot.self();
        CombatSnapshot.Options options = snapshot.options();
        if (options.canMelee()) {
            return new CombatDecision(CombatDecision.Action.ATTACK, reason);
        }
        if (self.bowAvailable() && releaseAllowed(snapshot)) {
            return new CombatDecision(CombatDecision.Action.FIRE_BOW, reason);
        }
        if (self.bowAvailable() && !self.firingLaneClear()) {
            return new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.UNSAFE_LANE);
        }
        return new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reason);
    }

    /** A bow may only be released when the target is eligible and the lane is clear of obstacles and allies. */
    public static boolean releaseAllowed(CombatSnapshot snapshot) {
        CombatSnapshot.Self self = snapshot.self();
        CombatSnapshot.Target target = snapshot.target();
        return target.present()
                && target.visible()
                && target.attackable()
                && self.bowAvailable()
                && self.firingLaneClear()
                && !self.allyInLane();
    }

    /**
     * Bounded lead aim. The projection is capped at {@code maxTicks} and the lead offset never exceeds what the
     * projectile can travel in that time, so a distant or fast target cannot produce an unbounded aim point.
     */
    public static Lead leadPoint(
            double targetX,
            double targetY,
            double targetZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            double shooterX,
            double shooterY,
            double shooterZ,
            double projectileSpeed,
            int maxTicks) {
        if (projectileSpeed <= 0.0 || maxTicks <= 0) {
            return new Lead(targetX, targetY, targetZ, 0);
        }
        double dx = targetX - shooterX;
        double dy = targetY - shooterY;
        double dz = targetZ - shooterZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int ticks = (int) Math.min(maxTicks, Math.ceil(distance / projectileSpeed));
        double leadX = velocityX * ticks;
        double leadY = velocityY * ticks;
        double leadZ = velocityZ * ticks;
        double maxLead = projectileSpeed * maxTicks;
        double leadLength = Math.sqrt(leadX * leadX + leadY * leadY + leadZ * leadZ);
        if (leadLength > maxLead) {
            double scale = maxLead / leadLength;
            leadX *= scale;
            leadY *= scale;
            leadZ *= scale;
        }
        return new Lead(targetX + leadX, targetY + leadY, targetZ + leadZ, ticks);
    }

    public record Lead(double x, double y, double z, int ticks) {}

    /** Threat kinds in the order phase 6 prioritises them. */
    public enum ThreatKind {
        ROUTINE(100),
        RANGED_CLEAR_LANE(200),
        ABOUT_TO_HIT_PROTECTED(300),
        IMMINENT_EXPLOSIVE(400);

        private final int priority;

        ThreatKind(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    /**
     * Target score for stable selection: kind first, then proximity, with a penalty for switching away and a hard
     * penalty for a target outside the configured pursuit bound.
     */
    public static double targetScore(
            ThreatKind kind, double distance, boolean currentTarget, double anchorDistance, int pursuitLimit) {
        double score = kind.priority() - distance;
        score += currentTarget ? 15.0 : -10.0;
        if (anchorDistance > pursuitLimit) {
            score -= 100.0;
        }
        return score;
    }

    /** Emits a notice once per continuous condition. Pure so the dedupe is unit-tested. */
    public static final class NoticeGate {
        private String last;

        public boolean shouldEmit(String key) {
            if (key == null || key.equals(last)) {
                return false;
            }
            last = key;
            return true;
        }

        public void clear() {
            last = null;
        }
    }
}
