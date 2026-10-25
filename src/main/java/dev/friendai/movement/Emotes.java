package dev.friendai.movement;

import java.util.Locale;
import java.util.Optional;

/** Short scripted gestures. A script is a function of the tick, so the task that plays it keeps no table. */
public final class Emotes {
    private Emotes() {}

    public enum Kind {
        WAVE(32),
        NOD(24),
        SHAKE_HEAD(24),
        JUMP_FOR_JOY(30),
        BOW(28),
        SPIN(20);

        private final int duration;

        Kind(int duration) {
            this.duration = duration;
        }

        /** How many ticks the gesture lasts. */
        public int duration() {
            return duration;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            String wanted = text.toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.label().equals(wanted)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What the body does on one tick of a gesture.
     *
     * @param yawOffset degrees added to the facing the gesture started from
     * @param pitch     head pitch in degrees
     * @param sneak     whether the body is crouched
     * @param swing     whether to start an arm swing
     * @param jump      whether to jump
     */
    public record Pose(float yawOffset, float pitch, boolean sneak, boolean swing, boolean jump) {
        static final Pose REST = new Pose(0.0f, 0.0f, false, false, false);
    }

    /** The pose at {@code tick} (0-based) of a gesture; {@link Pose#REST} outside its duration. */
    public static Pose at(Kind kind, int tick) {
        if (tick < 0 || tick >= kind.duration()) {
            return Pose.REST;
        }
        return switch (kind) {
            case WAVE -> new Pose(0.0f, -10.0f, false, tick % 8 == 0, false);
            case NOD -> new Pose(0.0f, (float) (25.0 * Math.abs(Math.sin(Math.PI * tick / 12.0))), false, false, false);
            case SHAKE_HEAD -> new Pose((float) (35.0 * Math.sin(2.0 * Math.PI * tick / 8.0)), 0.0f, false, false, false);
            case JUMP_FOR_JOY -> new Pose(0.0f, -15.0f, false, tick == 0 || tick == 15, tick == 0 || tick == 15);
            case BOW -> tick >= 4 && tick < 22
                    ? new Pose(0.0f, 40.0f, true, false, false)
                    : Pose.REST;
            case SPIN -> new Pose(360.0f * tick / kind.duration(), 0.0f, false, false, false);
        };
    }
}
