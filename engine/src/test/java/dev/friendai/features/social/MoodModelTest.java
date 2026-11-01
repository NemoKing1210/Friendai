package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MoodModelTest {
    @Test
    void dangerAndCombatOverrideEverything() {
        MoodModel model = new MoodModel();
        assertEquals(Mood.WORRIED, model.update(true, false, true, false, false, 0));
        assertEquals(Mood.COMBAT, model.update(false, true, true, false, false, 1));
        assertEquals(Mood.FOCUSED, model.update(false, false, true, false, false, 100));
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
}
