package dev.friendai.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExperienceModelTest {
    @Test
    void levelBoundaries() {
        assertEquals(0, ExperienceModel.pointsForLevel(0));
        assertEquals(7, ExperienceModel.pointsForLevel(1));
        assertEquals(352, ExperienceModel.pointsForLevel(16));
        assertEquals(394, ExperienceModel.pointsForLevel(17));
        assertEquals(1395, ExperienceModel.pointsForLevel(30));
        assertEquals(1507, ExperienceModel.pointsForLevel(31));

        ExperienceModel model = new ExperienceModel();
        model.add(352);
        assertEquals(16, model.level());
        assertEquals(0.0f, model.progress(), 1e-4f);
        model.add(1);
        assertEquals(16, model.level());
        assertEquals(1 / 42f, model.progress(), 1e-4f);
    }

    @Test
    void spendLevelsDropsToTheLowerLevelStart() {
        ExperienceModel model = new ExperienceModel();
        model.load(ExperienceModel.pointsForLevel(5) + 3);
        assertTrue(model.spendLevels(2));
        assertEquals(3, model.level());
        assertEquals(0.0f, model.progress(), 1e-4f);
        assertFalse(model.spendLevels(10));
        assertEquals(3, model.level());
        assertFalse(model.spendLevels(-1));
        assertEquals(3, model.level());
    }

    @Test
    void ignoresNonPositiveAdds() {
        ExperienceModel model = new ExperienceModel();
        model.add(0);
        model.add(-5);
        assertEquals(0, model.points());
    }

    @Test
    void capsAndClampsLoad() {
        ExperienceModel model = new ExperienceModel();
        model.add(Integer.MAX_VALUE);
        assertEquals(ExperienceModel.MAX_POINTS, model.points());
        assertTrue(model.level() > 0);
        model.add(Integer.MAX_VALUE);
        assertEquals(ExperienceModel.MAX_POINTS, model.points());
        model.load(-10);
        assertEquals(0, model.level());
    }

    @Test
    void loadLevelRoundTrip() {
        ExperienceModel model = new ExperienceModel();
        model.loadLevel(10, 0.5f);
        assertEquals(10, model.level());
        assertEquals(0.5f, model.progress(), 0.05f);
    }
}
