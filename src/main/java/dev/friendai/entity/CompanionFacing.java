package dev.friendai.entity;

import dev.friendai.features.navigation.movement.BodyFacing;
import dev.friendai.features.navigation.movement.Facing;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The companion's single body-orientation writer. Every action feeds a claim here; once per server tick it applies
 * the claim to the vanilla fields that are actually sent to clients: {@code yRot} (the rotation packet), {@code
 * yHeadRot} (the head packet) and {@code xRot}; {@code yBodyRot} is kept in step server-side. The client derives the
 * rendered torso from {@code yRot}, so no extra synced field is needed.
 *
 * <p>The pure policy and its caps live in {@link BodyFacing}; this class only converts points and movement vectors
 * into yaw and pitch and writes the result when it changed.
 */
public final class CompanionFacing {
    /** Below this horizontal speed a travel claim carries no direction; keep the yaw and only change the pitch. */
    private static final double MIN_TRAVEL = 0.05;
    /** Below this horizontal movement the companion is standing still: vanilla navigation then keeps the body. */
    private static final double MIN_WALK = 0.01;

    private final CompanionEntity companion;
    private final BodyFacing policy = new BodyFacing();

    CompanionFacing(CompanionEntity companion) {
        this.companion = companion;
    }

    /** Aims body and head at a world point for {@code ticks} ticks; refreshed by the action every tick. */
    public void lookAt(double x, double y, double z, int ticks) {
        Vec3 eye = companion.getEyePosition();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        policy.aim(Facing.yawToward(dx, dz), Facing.pitchToward(dx, dy, dz), ticks);
    }

    public void lookAt(Vec3 point, int ticks) {
        lookAt(point.x, point.y, point.z, ticks);
    }

    public void lookAt(Entity target, int ticks) {
        Vec3 eye = target.getEyePosition();
        lookAt(eye.x, eye.y, eye.z, ticks);
    }

    /** Claims body and head at an explicit yaw and pitch. */
    public void aim(float yaw, float pitch, int ticks) {
        policy.aim(yaw, pitch, ticks);
    }

    /** Claims only the yaw, for a turn or a gesture that must leave the pitch alone. */
    public void aimYaw(float yaw, int ticks) {
        policy.aimYaw(yaw, ticks);
    }

    /** A direction of travel: yaw from the horizontal part, pitch from the climb or sink. */
    public void travel(Vec3 movement) {
        double horizontal = Math.hypot(movement.x, movement.z);
        float pitch = Facing.pitchToward(movement.x, movement.y, movement.z);
        if (horizontal < MIN_TRAVEL) {
            policy.travelPitch(pitch);
        } else {
            policy.travel(Facing.yawToward(movement.x, movement.z), pitch);
        }
    }

    /** The heading a walk already computed. */
    public void travelYaw(float yaw) {
        policy.travelYaw(yaw);
    }

    /** Drops the current claim; used when the companion is dismissed or stopped. */
    public void release() {
        policy.clear();
    }

    public boolean hasClaim() {
        return policy.hasClaim();
    }

    public BodyFacing.Source source() {
        return policy.source();
    }

    public float desiredYaw() {
        return policy.targetYaw();
    }

    public float desiredPitch() {
        return policy.targetPitch();
    }

    /** Applies the claim once per server tick, after the task runner and the body's own gaze. */
    void tick() {
        if (!policy.hasClaim()) {
            double dx = companion.getX() - companion.xo;
            double dz = companion.getZ() - companion.zo;
            if (dx * dx + dz * dz <= MIN_WALK * MIN_WALK) {
                return;
            }
            // Vanilla navigation only writes yRot; a moving companion should carry its head along too.
            policy.travelYaw(Facing.yawToward(dx, dz));
        }
        policy.step(companion.getYRot(), companion.getXRot(), companion.getYHeadRot());
        if (policy.bodyChanged()) {
            companion.setYRot(policy.bodyYaw());
            companion.setYBodyRot(policy.bodyYaw());
        }
        if (policy.headChanged()) {
            companion.setYHeadRot(policy.headYaw());
        }
        if (policy.pitchChanged()) {
            companion.setXRot(policy.pitch());
        }
    }
}
