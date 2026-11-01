package dev.friendai.features.social;

/**
 * A bounded affect model: danger and combat override immediately, work focuses, a finished or failed job pulses a
 * pleased or displeased mood for a short window, and calm returns by itself. Pure and allocation-free.
 */
public final class MoodModel {
    public static final int PULSE_TICKS = 80;

    private Mood mood = Mood.CALM;
    private int pulseUntil;

    public Mood update(boolean danger, boolean combat, boolean work, boolean finished, boolean failed, int tick) {
        if (danger) {
            mood = Mood.WORRIED;
            return mood;
        }
        if (combat) {
            mood = Mood.COMBAT;
            return mood;
        }
        if (finished) {
            mood = Mood.PLEASED;
            pulseUntil = tick + PULSE_TICKS;
        } else if (failed) {
            mood = Mood.DISPLEASED;
            pulseUntil = tick + PULSE_TICKS;
        }
        if (tick < pulseUntil) {
            return mood;
        }
        mood = work ? Mood.FOCUSED : Mood.CALM;
        return mood;
    }

    public Mood mood() {
        return mood;
    }
}
