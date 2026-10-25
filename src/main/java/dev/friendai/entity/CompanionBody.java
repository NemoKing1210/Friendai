package dev.friendai.entity;

import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The companion's body language that outlives a single task: crouching, sitting, a held gaze and the small
 * preferences that shape how it idles. Ticked by the entity; none of it is saved, so a reload starts neutral.
 */
public final class CompanionBody {
    /** Shortest and longest distance the companion may keep from its owner while following. */
    public static final double MIN_FOLLOW_DISTANCE = 1.0;
    public static final double MAX_FOLLOW_DISTANCE = 8.0;
    public static final double DEFAULT_FOLLOW_DISTANCE = 3.0;

    private static final Identifier SNEAK_SLOWDOWN_ID = Identifier.fromNamespaceAndPath("friendai", "sneaking");
    /** Crouch-walking is about a third slower than walking upright. */
    private static final double SNEAK_SLOWDOWN = -0.3;

    private final CompanionEntity companion;
    private boolean sneaking;
    /** Ticks of crouching left; negative while the crouch is open-ended. */
    private int sneakTicks;
    private boolean sitting;
    private boolean crouchApplied;
    private int lookTicks;
    @Nullable private Supplier<Vec3> lookTarget;
    private boolean idleLook = true;
    private boolean idleGestures;
    private double followDistance = DEFAULT_FOLLOW_DISTANCE;

    CompanionBody(CompanionEntity companion) {
        this.companion = companion;
    }

    public boolean sneaking() {
        return sneaking;
    }

    /** Crouches or stands up. {@code ticks} below 1 means until released. */
    public void setSneaking(boolean on, int ticks) {
        this.sneaking = on;
        this.sneakTicks = on && ticks > 0 ? ticks : -1;
        apply();
    }

    public boolean sitting() {
        return sitting;
    }

    /** Sits down (a low crouch) or stands up. A seated companion does not walk: the sitting task keeps it put. */
    public void setSitting(boolean on) {
        this.sitting = on;
        apply();
    }

    /** True while the gaze is held on a target, so idle glances and other goals leave the look alone. */
    public boolean looking() {
        return lookTicks > 0 && lookTarget != null;
    }

    /** Keeps looking at a moving point for {@code ticks} ticks; the supplier returns null when the target is gone. */
    public void holdLook(Supplier<Vec3> target, int ticks) {
        this.lookTarget = target;
        this.lookTicks = Math.max(1, ticks);
    }

    public void releaseLook() {
        this.lookTicks = 0;
        this.lookTarget = null;
    }

    public boolean idleLook() {
        return idleLook;
    }

    public void setIdleLook(boolean on) {
        this.idleLook = on;
    }

    /** Whether the companion makes small gestures on its own while it idles beside its owner. */
    public boolean idleGestures() {
        return idleGestures;
    }

    public void setIdleGestures(boolean on) {
        this.idleGestures = on;
    }

    public double followDistance() {
        return followDistance;
    }

    public void setFollowDistance(double blocks) {
        this.followDistance = Math.max(MIN_FOLLOW_DISTANCE, Math.min(MAX_FOLLOW_DISTANCE, blocks));
    }

    /** Puts every transient posture back: stands, uncrouches and stops looking. Used on dismissal and by {@code stop}. */
    public void reset() {
        sneaking = false;
        sitting = false;
        sneakTicks = 0;
        releaseLook();
        apply();
    }

    void tick() {
        if (sneakTicks > 0 && --sneakTicks == 0) {
            sneaking = false;
            apply();
        }
        if (looking()) {
            Vec3 target = lookTarget.get();
            if (target == null) {
                releaseLook();
            } else {
                companion.getLookControl().setLookAt(target.x, target.y, target.z);
                lookTicks--;
            }
        }
        // The pose is re-asserted because the entity's own pose update would otherwise stand it up again.
        if (crouched() && !companion.isSleeping() && companion.getPose() != Pose.CROUCHING) {
            companion.setPose(Pose.CROUCHING);
        }
    }

    private boolean crouched() {
        return sneaking || sitting;
    }

    private void apply() {
        boolean crouch = crouched();
        if (crouch != crouchApplied) {
            crouchApplied = crouch;
            companion.setShiftKeyDown(crouch);
            if (!companion.isSleeping()) {
                companion.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
            }
        }
        AttributeInstance speed = companion.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        // Sitting never walks, so only a crouch-walk is slowed.
        boolean slow = sneaking && !sitting;
        if (slow && !speed.hasModifier(SNEAK_SLOWDOWN_ID)) {
            speed.addTransientModifier(new AttributeModifier(
                    SNEAK_SLOWDOWN_ID, SNEAK_SLOWDOWN, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!slow && speed.hasModifier(SNEAK_SLOWDOWN_ID)) {
            speed.removeModifier(SNEAK_SLOWDOWN_ID);
        }
    }
}
