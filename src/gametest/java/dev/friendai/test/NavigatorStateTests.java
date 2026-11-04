package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.navigation.minecraft.nav.Navigator;
import dev.friendai.foundation.spatial.GoalPoint;
import dev.friendai.task.TaskContext;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The navigator's own state machine, driven directly so one case costs a few ticks: a new goal that is already reached,
 * and a route that was stopped on purpose. Both used to be masked by the executor of the previous route.
 */
public class NavigatorStateTests {

    private static CompanionEntity walker(GameTestHelper h, float x, float y, float z) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, x, y, z);
        // Several GameTests share one world: the search and the walk must stay inside this structure. The box is
        // relative to the planted platform, not to the structure floor, because these tests build at a raised Y.
        c.setPathBounds(new AABB(h.absoluteVec(new Vec3(-2, y - 3.0, -2)), h.absoluteVec(new Vec3(18, y + 12.0, 18))));
        return c;
    }

    @GameTest(maxTicks = 300)
    public void aReachedNewGoalEndsNavigationWithoutWalkingTheOldRoute(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 16);
        CompanionEntity c = walker(h, 2, 301, 2);
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator navigator = new Navigator(context, true);
        Vec3 far = h.absoluteVec(new Vec3(12.5, 301, 12.5));
        navigator.setGoal(new GoalPoint(far.x, far.y, far.z, 1.0, 2.0));

        boolean[] reaimed = {false};
        int[] ticksSinceReaim = {0};
        h.succeedWhen(() -> {
            navigator.tick(context);
            if (!reaimed[0]) {
                h.assertTrue(
                        navigator.plan() != null,
                        "wait for the first route: " + navigator.state() + " " + navigator.lastSearch());
                // The body already stands inside the new goal, so the search can answer it at once with an empty
                // route. Walking the old route to its end first is the defect this pins.
                navigator.setGoal(new GoalPoint(c.getX(), c.getY(), c.getZ(), 1.0, 2.0));
                reaimed[0] = true;
                navigator.tick(context);
            }
            ticksSinceReaim[0]++;
            String state = navigator.state().name();
            h.assertTrue(
                    ticksSinceReaim[0] <= 6,
                    "a goal the body already stands in must not wait for the old route: still " + state + " after "
                            + ticksSinceReaim[0] + " ticks " + navigator.lastSearch());
            h.assertTrue(
                    "ARRIVED".equals(state),
                    "a goal the body already stands in must arrive at once, not finish the old route: " + state + " "
                            + navigator.lastSearch());
            h.assertTrue(navigator.plan() == null, "the abandoned route must not keep driving the body");
        });
    }

    @GameTest(maxTicks = 200)
    public void aStoppedNavigatorParksWithoutSearching(GameTestHelper h) {
        GameTestSupport.platformAt(h, 305, 16);
        CompanionEntity c = walker(h, 2, 306, 2);
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator navigator = new Navigator(context, true);
        Vec3 far = h.absoluteVec(new Vec3(12.5, 306, 12.5));
        navigator.setGoal(new GoalPoint(far.x, far.y, far.z, 1.0, 2.0));

        boolean[] stopped = {false};
        String[] stateAfterStop = {""};
        h.succeedWhen(() -> {
            navigator.tick(context);
            if (!stopped[0]) {
                h.assertTrue(
                        navigator.plan() != null,
                        "wait for the route: " + navigator.state() + " " + navigator.lastSearch());
                navigator.stop(context);
                stateAfterStop[0] = navigator.state().name();
                stopped[0] = true;
            }
            h.assertTrue(
                    "SEARCHING".equals(stateAfterStop[0]),
                    "a stopped navigator must not claim to be moving: " + stateAfterStop[0]);
            h.assertFalse(navigator.searching(), "a stopped navigator must not start a search");
            h.assertTrue(navigator.plan() == null, "a stopped navigator must not walk a route");
            h.assertFalse(c.pathing(), "a stopped navigator must leave pathing off");
            // The same goal must still wake a parked navigator: stop() leaves the state at SEARCHING, which must
            // not look like a search already in progress.
            navigator.setGoal(new GoalPoint(far.x, far.y, far.z, 1.0, 2.0));
            h.assertTrue(navigator.searching(), "a new goal must restart a stopped navigator");
        });
    }

    @GameTest(maxTicks = 400)
    public void aDirectlyDrivenRouteStillSprintsIntoALeap(GameTestHelper h) {
        int base = 240;
        GameTestSupport.platformAt(h, base, 16);
        // A two-block trench across the whole platform: the jump is the only way over, and it needs a run-up.
        for (int z = 0; z < 16; z++) {
            for (int y = base - 4; y <= base; y++) {
                h.setBlock(new BlockPos(8, y, z), Blocks.AIR);
                h.setBlock(new BlockPos(9, y, z), Blocks.AIR);
            }
        }
        CompanionEntity c = walker(h, 2, base + 1, 8);
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator navigator = new Navigator(context, true);
        Vec3 goal = h.absoluteVec(new Vec3(13.5, base + 1, 8.5));
        navigator.setGoal(new GoalPoint(goal.x, goal.y, goal.z, 1.0, 2.0));

        h.succeedWhen(() -> {
            navigator.tick(context);
            h.assertFalse(
                    navigator.state() == Navigator.State.UNREACHABLE,
                    "the gap must still be walkable: " + navigator.lastSearch());
            // Sprinting is what pays for the leap: a walking jump costs 0.05 exhaustion, a running one 0.2.
            h.assertTrue(
                    c.hunger().exhaustion() > 0.15f,
                    "a route driven by its own navigator must still sprint into the leap: " + navigator.state()
                            + " exhaustion " + c.hunger().exhaustion());
        });
    }
}
