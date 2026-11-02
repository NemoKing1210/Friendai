package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MoodModelTest {
    @Test
    void dangerTurnsWorriedAfterItsWindowAndCombatOverridesAtOnce() {
        MoodModel model = new MoodModel();
        for (int tick = 0; tick < MoodModel.DANGER_ENTER_TICKS - 1; tick++) {
            assertEquals(Mood.CALM, model.update(true, false, false, false, false, tick));
        }
        assertEquals(Mood.WORRIED, model.update(true, false, false, false, false, MoodModel.DANGER_ENTER_TICKS - 1));
        assertEquals(Mood.COMBAT, model.update(false, true, true, false, false, 50));
        assertEquals(Mood.FOCUSED, model.update(false, false, true, false, false, 100));
    }

    @Test
    void worryLingersForItsQuietWindowAndThenReleases() {
        MoodModel model = new MoodModel();
        for (int tick = 0; tick < MoodModel.DANGER_ENTER_TICKS; tick++) {
            model.update(true, false, false, false, false, tick);
        }
        int afterDanger = MoodModel.DANGER_ENTER_TICKS;
        for (int tick = afterDanger; tick < afterDanger + MoodModel.DANGER_EXIT_TICKS - 1; tick++) {
            assertEquals(Mood.WORRIED, model.update(false, false, false, false, false, tick));
        }
        assertEquals(
                Mood.CALM, model.update(false, false, false, false, false, afterDanger + MoodModel.DANGER_EXIT_TICKS));
    }

    @Test
    void aFinishedJobPulsesPleasedThenCalms() {
        MoodModel model = new MoodModel();
        assertEquals(Mood.PLEASED, model.update(false, false, false, true, false, 0));
        assertEquals(Mood.PLEASED, model.update(false, false, false, false, false, 10));
        assertEquals(Mood.CALM, model.update(false, false, false, false, false, MoodModel.PULSE_TICKS + 1));
    }

    @Test
    void aFailedJobPulsesDispleasedButWorkWinsAfterward() {
        MoodModel model = new MoodModel();
        assertEquals(Mood.DISPLEASED, model.update(false, false, false, false, true, 0));
        assertEquals(Mood.FOCUSED, model.update(false, false, true, false, false, MoodModel.PULSE_TICKS + 1));
    }

    @Test
    void aDeedPulsesButNeverOverDangerOrCombat() {
        MoodModel calm = new MoodModel();
        assertEquals(Mood.PLEASED, calm.pulse(Mood.PLEASED, 0));
        assertEquals(Mood.PLEASED, calm.update(false, false, false, false, false, 10));
        assertEquals(Mood.CALM, calm.update(false, false, false, false, false, MoodModel.PULSE_TICKS + 1));

        MoodModel fighting = new MoodModel();
        fighting.update(false, true, false, false, false, 0);
        assertEquals(Mood.COMBAT, fighting.pulse(Mood.PLEASED, 0));
    }

    @Test
    void anExplicitCueMovesTheFaceAndAnUnknownOneDoesNot() {
        MoodModel model = new MoodModel();
        assertEquals(Mood.DISPLEASED, model.cue(SpeechCue.of("sorry, my bad"), 0));
        MoodModel quiet = new MoodModel();
        assertEquals(Mood.CALM, quiet.cue(SpeechCue.of("bring me 3 logs"), 0));
    }

    @Test
    void aCueCannotPaintOverWorryAndTheFaceCalmsAfterItsWindow() {
        MoodModel model = new MoodModel();
        for (int tick = 0; tick < MoodModel.DANGER_ENTER_TICKS; tick++) {
            model.update(true, false, false, false, false, tick);
        }
        assertEquals(Mood.WORRIED, model.mood());

        assertEquals(Mood.WORRIED, model.cue(SpeechCue.GREETING, MoodModel.DANGER_ENTER_TICKS));
        assertEquals(Mood.WORRIED, model.mood(), "danger outranks a friendly cue");

        int lastWorriedTick = MoodModel.DANGER_ENTER_TICKS + MoodModel.DANGER_EXIT_TICKS - 2;
        assertEquals(Mood.WORRIED, model.update(false, false, false, false, false, lastWorriedTick));
        assertEquals(Mood.CALM, model.update(false, false, false, false, false, lastWorriedTick + 2));
    }

    @Test
    void theExpressionsAreTheClosedSetTheAccessorAndRendererExpect() {
        assertEquals(
                List.of(Mood.CALM, Mood.FOCUSED, Mood.WORRIED, Mood.PLEASED, Mood.DISPLEASED, Mood.COMBAT),
                List.of(Mood.values()),
                "the synced ordinal and the renderer both depend on this order");
    }
}
