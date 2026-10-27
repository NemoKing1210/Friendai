package dev.friendai.fire;

import dev.friendai.fire.BurningCell.Kind;
import dev.friendai.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FireTextTest {

    private static final Pos POS = Pos.of(10, 64, 3);

    @Test
    void describeNamesEveryStepAndItsPosition() {
        ExtinguishPlan plan = new ExtinguishPlan(new FireTarget.Block(POS),
                List.of(new ExtinguishStep.PlaceWater(POS), new ExtinguishStep.RecoverBucket(POS)), List.of());

        String text = FireText.describe(plan);

        assertTrue(text.contains("10 64 3"), text);
        assertTrue(text.contains("water"), text);
        assertTrue(text.contains("bucket"), text);
    }

    @Test
    void aRefusalKeepsItsReason() {
        String text = FireText.gaveUp("the nether has no water");

        assertTrue(text.contains("the nether has no water"), text);
    }

    @Test
    void aFinishedPlanCountsWhatItPutOut() {
        assertTrue(FireText.done(3).contains("3"), FireText.done(3));
    }

    @Test
    void describeMarksACampfireStepAsWater() {
        ExtinguishPlan plan = new ExtinguishPlan(new FireTarget.Block(POS),
                List.of(new ExtinguishStep.DouseCampfire(POS)), List.of());

        String text = FireText.describe(plan);

        assertTrue(text.contains("campfire"), text);
        assertTrue(text.contains("water"), text);
    }
}
