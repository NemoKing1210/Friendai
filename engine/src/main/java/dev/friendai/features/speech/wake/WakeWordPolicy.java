package dev.friendai.features.speech.wake;

import java.util.Objects;

/**
 * The lifecycle of one hands-free utterance: off until the owner explicitly arms it, capture only on a locally
 * detected wake word, a bounded utterance ended by silence or a hard tick limit, then a cooldown before the next one.
 *
 * <p>
 * Pure and tick-driven: there is no clock here, every duration is a count of {@link #tick()} calls, so the same event
 * sequence always produces the same states. The policy keeps counters only — no audio is stored, copied or handed
 * on; pre-wake monitoring and the utterance buffer live in the client, which feeds this policy a detector hit and a
 * per-tick loudness verdict and discards the samples itself. Disarming from any state drops straight back to
 * {@link State#IDLE} with every counter cleared, so a cancelled utterance leaves nothing to resume.
 */
public final class WakeWordPolicy {
    /** All durations are ticks (20 per second) on the server/client game loop, never wall clock. */
    public record Config(boolean enabled, int cooldownTicks, int maxUtteranceTicks, int silenceTicks) {
        /** Off by default; a 30 s utterance cap, 1 s of silence to end it, and 2 s before the next one. */
        public static final Config DEFAULTS = new Config(false, 40, 600, 20);

        public Config {
            if (cooldownTicks < 0 || cooldownTicks > 1_200)
                throw new IllegalArgumentException("cooldownTicks must be between 0 and 1200");
            if (maxUtteranceTicks < 1 || maxUtteranceTicks > 7_200)
                throw new IllegalArgumentException("maxUtteranceTicks must be between 1 and 7200");
            if (silenceTicks < 1 || silenceTicks > maxUtteranceTicks)
                throw new IllegalArgumentException("silenceTicks must be between 1 and maxUtteranceTicks");
        }
    }

    public enum State {
        /** Not listening: disabled, disarmed, or never armed. A detector hit here is ignored. */
        IDLE,
        /** Listening for the wake word; the only state in which a hit starts capture. */
        ARMED,
        /** Recording the utterance; ends on silence, the utterance cap, or {@link #disarm()}. */
        CAPTURING,
        /** Capture just ended; hits are ignored until the cooldown runs out and returns to ARMED. */
        COOLDOWN
    }

    public enum Event {
        DETECTOR_HIT,
        LEVEL_ABOVE_THRESHOLD,
        LEVEL_BELOW_THRESHOLD,
        TICK
    }

    private final Config config;

    private State state = State.IDLE;
    private int captureTicks;
    private int silentTicks;
    private int cooldownRemaining;
    private boolean lastSampleSilent;

    public WakeWordPolicy(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Explicit owner action; a disabled policy never arms, and a cooldown cannot be skipped by re-arming. */
    public State arm() {
        if (config.enabled() && state == State.IDLE) {
            state = State.ARMED;
        }
        return state;
    }

    /** Stops capture or listening right away and clears every counter, including a running cooldown. */
    public State disarm() {
        reset();
        return state;
    }

    public State feed(Event event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case DETECTOR_HIT -> detectorHit();
            case LEVEL_ABOVE_THRESHOLD -> levelAboveThreshold();
            case LEVEL_BELOW_THRESHOLD -> levelBelowThreshold();
            case TICK -> tick();
        };
    }

    /** A locally detected wake word starts capture only while armed; hits in IDLE or COOLDOWN change nothing. */
    public State detectorHit() {
        if (state == State.ARMED) {
            state = State.CAPTURING;
            captureTicks = 0;
            silentTicks = 0;
            lastSampleSilent = false;
        }
        return state;
    }

    /** Sound keeps the utterance alive; loudness before a hit never starts capture on its own. */
    public State levelAboveThreshold() {
        if (state == State.CAPTURING) {
            lastSampleSilent = false;
            silentTicks = 0;
        }
        return state;
    }

    /** Quiet is only remembered; the tick that follows counts it towards the silence limit. */
    public State levelBelowThreshold() {
        if (state == State.CAPTURING) {
            lastSampleSilent = true;
        }
        return state;
    }

    /** Advances one game tick: utterance age, silence, or cooldown depending on the state. */
    public State tick() {
        switch (state) {
            case CAPTURING -> {
                captureTicks++;
                silentTicks = lastSampleSilent ? silentTicks + 1 : 0;
                if (silentTicks >= config.silenceTicks() || captureTicks >= config.maxUtteranceTicks()) {
                    finishCapture();
                }
            }
            case COOLDOWN -> {
                cooldownRemaining--;
                if (cooldownRemaining <= 0) {
                    state = State.ARMED;
                }
            }
            default -> {}
        }
        return state;
    }

    public State state() {
        return state;
    }

    public Config config() {
        return config;
    }

    public boolean capturing() {
        return state == State.CAPTURING;
    }

    /** Ticks the current utterance has lasted; zero outside capture. */
    public int captureTicks() {
        return captureTicks;
    }

    /** Consecutive silent ticks so far in this utterance; zero outside capture. */
    public int silentTicks() {
        return silentTicks;
    }

    public int cooldownRemaining() {
        return cooldownRemaining;
    }

    private void finishCapture() {
        captureTicks = 0;
        silentTicks = 0;
        lastSampleSilent = false;
        cooldownRemaining = config.cooldownTicks();
        state = cooldownRemaining > 0 ? State.COOLDOWN : State.ARMED;
    }

    private void reset() {
        state = State.IDLE;
        captureTicks = 0;
        silentTicks = 0;
        cooldownRemaining = 0;
        lastSampleSilent = false;
    }
}
