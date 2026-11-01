package dev.friendai.features.social;

/**
 * A bounded affect model: danger and combat override immediately, work focuses, a finished or failed job pulses a
 * pleased or displeased mood for a short window, and calm returns by itself. Pure and allocation-free.
 */
public final class MoodModel {
    public static final int PULSE_TICKS = 80;
    /** Danger must last this long before the face turns worried, so one burning tick is not fear. */
    public static final int DANGER_ENTER_TICKS = 10;
    /** Once worried, the face stays worried a little longer than the danger itself. */
    public static final int DANGER_EXIT_TICKS = 20;

    private Mood mood = Mood.CALM;
    private int pulseUntil;
    private int dangerTicks;
    private int quietTicks;
    private int lastTick = Integer.MIN_VALUE;

    public Mood update(boolean danger, boolean combat, boolean work, boolean finished, boolean failed, int tick) {
        // The caller may sample once every ten ticks; the windows are in ticks, so count the elapsed ones.
        int elapsed = lastTick == Integer.MIN_VALUE ? 1 : Math.max(0, tick - lastTick);
        lastTick = tick;
        if (danger) {
            dangerTicks += elapsed;
            quietTicks = 0;
        } else {
            dangerTicks = 0;
            quietTicks += elapsed;
        }
        if (dangerTicks >= DANGER_ENTER_TICKS) {
            mood = Mood.WORRIED;
            return mood;
        }
        if (combat) {
            mood = Mood.COMBAT;
            return mood;
        }
        if (mood == Mood.WORRIED && quietTicks < DANGER_EXIT_TICKS) {
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

    /**
     * One deed moves the face for the bounded pulse window. Danger and combat still win: a gift during a fight must not
     * paint a pleased face over a creeper.
     */
    public Mood pulse(Mood next, int tick) {
        if (next == Mood.WORRIED || next == Mood.COMBAT || mood == Mood.WORRIED || mood == Mood.COMBAT) {
            return mood;
        }
        mood = next;
        pulseUntil = tick + PULSE_TICKS;
        return mood;
    }

    /** Applies what the owner explicitly said; a cue the list does not know changes nothing. */
    public Mood cue(SpeechCue cue, int tick) {
        Mood next = cue == null ? null : cue.mood();
        return next == null ? mood : pulse(next, tick);
    }

    public Mood mood() {
        return mood;
    }
}
