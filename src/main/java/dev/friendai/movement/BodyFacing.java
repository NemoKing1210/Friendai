package dev.friendai.movement;

/**
 * Which way the body should point, from the action that currently claims it.
 *
 * <p>One claim at a time: an action (mining, placing, fighting, a gesture) outranks travel (a walk or a flight leg),
 * and travel outranks the idle glance, which is the vanilla look control and never reaches this class. The body turns
 * at most {@link #MAX_BODY_TURN} degrees per tick, the head at most {@link #MAX_HEAD_TURN}, the pitch at most
 * {@link #MAX_PITCH_TURN}; a part closer than {@link #HYSTERESIS} degrees to its target is not written at all, so a
 * straight walk or a settled aim sends no rotation packet and cannot jitter.
 *
 * <p>Pure and allocation-free: it only does yaw and pitch arithmetic, and {@link #step} returns immediately when
 * nothing claims the body. The entity adapter interprets the values and writes the vanilla fields.
 */
public final class BodyFacing {
    /** The most the body may turn in one tick, the same bound as {@code PathSteering}. */
    public static final float MAX_BODY_TURN = 30.0f;
    /** The most the head may turn in one tick; it leads the torso, up to the vanilla mob head cone. */
    public static final float MAX_HEAD_TURN = 75.0f;
    /** The widest the head may sit from the body, the same cone vanilla gives a mob. */
    public static final float MAX_HEAD_YAW = 75.0f;
    /** The most the pitch may change in one tick. */
    public static final float MAX_PITCH_TURN = 30.0f;
    /** A target closer than this to the value already written causes no write at all. */
    public static final float HYSTERESIS = 3.0f;
    /** How long a travel claim survives; refreshed by the mover every tick. */
    public static final int TRAVEL_HOLD_TICKS = 2;

    /** Why the body is being pointed, highest first. */
    public enum Source {
        NONE,
        TRAVEL,
        ACTION
    }

    private Source source = Source.NONE;
    private float targetYaw;
    private float targetPitch;
    private boolean yawOwned;
    private boolean pitchOwned;
    private int ticksLeft;

    private float bodyYaw;
    private float headYaw;
    private float pitch;
    private boolean bodyChanged;
    private boolean headChanged;
    private boolean pitchChanged;
    /**
     * The pitch the policy last held. Vanilla's {@code LookControl} resets {@code xRot} to 0 at the start of every
     * tick, so the entity's current pitch cannot carry the aim from one tick to the next; the claim must.
     */
    private float heldPitch;

    private boolean pitchSeeded;

    /** Claims the body and the head for an action, with the pitch; the caller refreshes it while the action runs. */
    public void aim(float yaw, float pitch, int ticks) {
        claim(Source.ACTION, yaw, pitch, true, true, ticks);
    }

    /** Claims only the body and head yaw, for a turn or a gesture that must not change the pitch. */
    public void aimYaw(float yaw, int ticks) {
        claim(Source.ACTION, yaw, 0.0f, true, false, ticks);
    }

    /** Claims a direction of travel, body and head, with the climb or sink angle as the pitch. */
    public void travel(float yaw, float pitch) {
        if (!travelAllowed()) {
            return;
        }
        claim(Source.TRAVEL, yaw, pitch, true, true, TRAVEL_HOLD_TICKS);
    }

    /** Claims the direction of a walk, yaw only: a walker's pitch is not its heading. */
    public void travelYaw(float yaw) {
        if (!travelAllowed()) {
            return;
        }
        claim(Source.TRAVEL, yaw, 0.0f, true, false, TRAVEL_HOLD_TICKS);
    }

    /** Claims only the pitch, for a vertical flight leg where the yaw must stay put. */
    public void travelPitch(float pitch) {
        if (!travelAllowed()) {
            return;
        }
        claim(Source.TRAVEL, 0.0f, pitch, false, true, TRAVEL_HOLD_TICKS);
    }

    /** Drops the claim: the body and head are left where they are. */
    public void clear() {
        source = Source.NONE;
        ticksLeft = 0;
        yawOwned = false;
        pitchOwned = false;
        pitchSeeded = false;
    }

    public boolean hasClaim() {
        return ticksLeft > 0;
    }

    public Source source() {
        return source;
    }

    public float targetYaw() {
        return targetYaw;
    }

    public float targetPitch() {
        return targetPitch;
    }

    public float bodyYaw() {
        return bodyYaw;
    }

    public float headYaw() {
        return headYaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean bodyChanged() {
        return bodyChanged;
    }

    public boolean headChanged() {
        return headChanged;
    }

    public boolean pitchChanged() {
        return pitchChanged;
    }

    /**
     * Advances the claim one tick against the values already on the entity.
     *
     * @return true when at least one of the body, head or pitch must be written
     */
    public boolean step(float currentYaw, float currentPitch, float currentHeadYaw) {
        bodyChanged = false;
        headChanged = false;
        pitchChanged = false;
        if (ticksLeft <= 0) {
            source = Source.NONE;
            return false;
        }
        if (yawOwned) {
            bodyChanged = Facing.difference(currentYaw, targetYaw) > HYSTERESIS;
            bodyYaw = bodyChanged ? Facing.stepToward(currentYaw, targetYaw, MAX_BODY_TURN) : currentYaw;
            float headTarget = clampToCone(bodyYaw, targetYaw, MAX_HEAD_YAW);
            headChanged = Facing.difference(currentHeadYaw, headTarget) > HYSTERESIS;
            headYaw = headChanged ? Facing.stepToward(currentHeadYaw, headTarget, MAX_HEAD_TURN) : currentHeadYaw;
        } else {
            bodyYaw = currentYaw;
            headYaw = currentHeadYaw;
        }
        if (pitchOwned) {
            float wanted = clampPitch(targetPitch);
            if (!pitchSeeded) {
                heldPitch = currentPitch;
                pitchSeeded = true;
            }
            if (Math.abs(wanted - heldPitch) > HYSTERESIS) {
                heldPitch = stepPitch(heldPitch, wanted, MAX_PITCH_TURN);
            }
            pitch = heldPitch;
            // Vanilla resets xRot to 0 each tick, so the held pitch must be re-asserted even when the aim is settled.
            pitchChanged = Math.abs(pitch - currentPitch) > HYSTERESIS;
        } else {
            pitch = currentPitch;
            pitchSeeded = false;
        }
        if (--ticksLeft <= 0) {
            ticksLeft = 0;
            source = Source.NONE;
            pitchSeeded = false;
        }
        return bodyChanged || headChanged || pitchChanged;
    }

    private void claim(Source kind, float yaw, float pitch, boolean ownYaw, boolean ownPitch, int ticks) {
        source = kind;
        targetYaw = Facing.normalize(yaw);
        targetPitch = clampPitch(pitch);
        yawOwned = ownYaw;
        pitchOwned = ownPitch;
        ticksLeft = Math.max(1, ticks);
    }

    private boolean travelAllowed() {
        return source != Source.ACTION || ticksLeft <= 0;
    }

    private static float clampPitch(float value) {
        return Math.max(-90.0f, Math.min(90.0f, value));
    }

    /** The nearest yaw to {@code targetYaw} that sits within {@code cone} degrees of {@code bodyYaw}. */
    private static float clampToCone(float bodyYaw, float targetYaw, float cone) {
        float delta = Facing.normalize(targetYaw - bodyYaw);
        if (Math.abs(delta) <= cone) {
            return Facing.normalize(targetYaw);
        }
        return Facing.normalize(bodyYaw + Math.copySign(cone, delta));
    }

    private static float stepPitch(float current, float target, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return clampPitch(target);
        }
        return clampPitch(current + Math.copySign(maxStep, delta));
    }
}
