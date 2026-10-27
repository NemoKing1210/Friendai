package dev.friendai.fire;

import dev.friendai.fire.BurningCell.Kind;
import dev.friendai.fire.BurningCell.Rails;
import dev.friendai.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirePlannerTest {

    private static final Pos FIRE_POS = Pos.of(10, 64, 3);
    private static final Pos SELF = Pos.of(0, 64, 0);

    private static FireFacts facts() {
        return new FireFacts(false, false, SELF, 0, 0, null, null, true);
    }

    private static FireFacts wet(Pos waterCell) {
        return new FireFacts(false, false, SELF, 1, 0, null, waterCell, true);
    }

    private static Rails free() {
        return new Rails(false, false, true, true, true, false);
    }

    private static BurningCell cell(Kind kind, Rails rails) {
        return new BurningCell(FIRE_POS, kind, rails);
    }

    @Test
    void waterFirstThenTheBucketComesBack() {
        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), wet(FIRE_POS),
                List.of(cell(Kind.FIRE, free())));

        assertEquals(List.of(new ExtinguishStep.PlaceWater(FIRE_POS),
                new ExtinguishStep.RecoverBucket(FIRE_POS)), plan.steps());
    }

    @Test
    void breaksWhenThereIsNoWater() {
        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), facts(),
                List.of(cell(Kind.FIRE, free())));

        assertEquals(List.of(new ExtinguishStep.BreakFire(FIRE_POS)), plan.steps());
    }

    @Test
    void neverWatersTheNether() {
        FireFacts nether = new FireFacts(true, false, SELF, 1, 0, null, FIRE_POS, true);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), nether,
                List.of(cell(Kind.FIRE, free())));

        assertEquals(List.of(new ExtinguishStep.BreakFire(FIRE_POS)), plan.steps());
        assertFalse(plan.steps().stream().anyMatch(step -> step instanceof ExtinguishStep.PlaceWater));
    }

    @Test
    void aCampfireGetsWaterNeverABreak() {
        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), wet(FIRE_POS),
                List.of(cell(Kind.LIT_CAMPFIRE, free())));

        assertEquals(List.of(new ExtinguishStep.DouseCampfire(FIRE_POS),
                new ExtinguishStep.RecoverBucket(FIRE_POS)), plan.steps());
    }

    @Test
    void keepsTheCookingCampfire() {
        Rails reserved = new Rails(true, false, true, true, true, false);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), wet(FIRE_POS),
                List.of(cell(Kind.LIT_CAMPFIRE, reserved)));

        assertEquals(1, plan.steps().size());
        assertTrue(plan.steps().getFirst() instanceof ExtinguishStep.GiveUp);
        assertEquals("it is my cooking station", ((ExtinguishStep.GiveUp) plan.steps().getFirst()).reason());
    }

    @Test
    void refusesAFireInAPortalFrame() {
        Rails frame = new Rails(false, true, false, true, true, false);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), facts(),
                List.of(cell(Kind.FIRE, frame)));

        assertEquals(List.of(new ExtinguishStep.GiveUp("it is inside a portal frame")), plan.steps());
    }

    @Test
    void aPortalFrameIsRefusedEvenWithWater() {
        Rails frame = new Rails(false, true, true, true, true, false);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), wet(FIRE_POS),
                List.of(cell(Kind.FIRE, frame)));

        assertEquals(List.of(new ExtinguishStep.GiveUp("it is inside a portal frame")), plan.steps());
    }

    @Test
    void refusesAProtectedCell() {
        Rails protectedCell = new Rails(false, false, false, false, true, true);
        FireFacts watered = new FireFacts(false, false, SELF, 1, 0, null, FIRE_POS, true);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), watered,
                List.of(cell(Kind.FIRE, protectedCell)));

        assertEquals(List.of(new ExtinguishStep.GiveUp("it is protected")), plan.steps());
    }

    @Test
    void selfEntersWaterWhenItStandsNear() {
        FireFacts facts = new FireFacts(false, false, SELF, 0, 0, Pos.of(2, 64, 0), null, true);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Self(), facts,
                List.of(new BurningCell(SELF, Kind.FIRE, free())));

        assertEquals(List.of(new ExtinguishStep.EnterWater(Pos.of(2, 64, 0))), plan.steps());
    }

    @Test
    void selfPrefersPowderSnowOverItsOwnBucket() {
        FireFacts facts = new FireFacts(false, false, SELF, 1, 1, null, SELF, true);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Self(), facts, List.of());

        assertEquals(List.of(new ExtinguishStep.StandInPowderSnow(SELF)), plan.steps());
    }

    @Test
    void selfGivesUpWithNoMeans() {
        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Self(), facts(), List.of());

        assertEquals(List.of(new ExtinguishStep.GiveUp("nothing to put it out with")), plan.steps());
    }

    @Test
    void ownerGetsWaterBesideThemNeverInTheirCell() {
        Pos beside = Pos.of(5, 64, 4);
        FireFacts facts = new FireFacts(false, false, SELF, 1, 0, null, beside, true);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Owner(), facts, List.of());

        assertEquals(List.of(new ExtinguishStep.PlaceWater(beside),
                new ExtinguishStep.RecoverBucket(beside)), plan.steps());
        assertFalse(plan.steps().contains(new ExtinguishStep.PlaceWater(Pos.of(4, 64, 4))));
    }

    @Test
    void anOwnerOutOfReachIsLeftAlone() {
        FireFacts facts = new FireFacts(false, false, SELF, 1, 0, null, Pos.of(30, 64, 30), false);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Owner(), facts, List.of());

        assertEquals(List.of(new ExtinguishStep.GiveUp("they are out of reach")), plan.steps());
    }

    @Test
    void rainingDoesNotStopABlock() {
        FireFacts facts = new FireFacts(false, true, SELF, 0, 0, null, null, true);

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), facts,
                List.of(cell(Kind.FIRE, free())));

        assertEquals(List.of(new ExtinguishStep.BreakFire(FIRE_POS)), plan.steps());
    }

    @Test
    void anAreaStopsAtTheFirstRefusal() {
        Pos second = Pos.of(11, 64, 3);
        List<BurningCell> cells = List.of(
                new BurningCell(FIRE_POS, Kind.FIRE, free()),
                new BurningCell(second, Kind.LIT_CAMPFIRE, new Rails(true, false, true, true, true, false)));

        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Area(List.of(FIRE_POS, second)), facts(), cells);

        assertEquals(List.of(new ExtinguishStep.BreakFire(FIRE_POS),
                new ExtinguishStep.GiveUp("it is my cooking station")), plan.steps());
    }

    @Test
    void anUnknownCellIsReportedAsAlreadyOut() {
        ExtinguishPlan plan = FirePlanner.plan(new FireTarget.Block(FIRE_POS), facts(), List.of());

        assertEquals(List.of(new ExtinguishStep.GiveUp("it is already out")), plan.steps());
    }
}
