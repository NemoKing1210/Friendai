package dev.friendai.features.navigation.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.task.Task;
import dev.friendai.task.TaskState;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Every navigation order survives a checkpoint: its parameters, and the quiet/legacy shapes older saves carry. */
class NavigationCheckpointsTest {

    private static <T extends Task> T restore(Optional<Task> task, Class<T> type) {
        assertTrue(task.isPresent(), "the checkpoint must decode");
        return type.cast(task.get());
    }

    @Test
    void aWalkKeepsItsDestinationAndSneaking() {
        GoToTask quiet = new GoToTask(new Vec3(1.5, 64.0, -2.25), true);

        GoToTask restored =
                restore(NavigationCheckpoints.goTo(new TaskState("go_to", quiet.saveData()), null), GoToTask.class);

        assertEquals(quiet.saveData(), restored.saveData(), "a restored walk must keep its destination and flag");
        assertTrue(restored.saveData().endsWith(",1"), restored.saveData());
    }

    @Test
    void aLegacyWalkWithoutTheFlagLoadsAsANormalWalk() {
        GoToTask restored = restore(
                NavigationCheckpoints.goTo(new TaskState("go_to", "1.000000,64.000000,2.000000"), null),
                GoToTask.class);

        assertEquals("1.000000,64.000000,2.000000,0", restored.saveData());
        assertNull(restored.dimension(), "a walk saved before dimensions were recorded stays unbound");
    }

    @Test
    void aBoundWalkKeepsTheDimensionItWasPlannedIn() {
        GoToTask walk = new GoToTask(new Vec3(10.0, 70.0, -4.0), false, "minecraft:the_nether");

        GoToTask restored =
                restore(NavigationCheckpoints.goTo(new TaskState("go_to", walk.saveData()), null), GoToTask.class);

        assertEquals(walk.saveData(), restored.saveData(), "a restored walk must keep its world");
        assertEquals("minecraft:the_nether", restored.dimension(), "the dimension must survive the checkpoint");
    }

    @Test
    void aWalkWithAnUnreadableDimensionIsNotRestored() {
        assertTrue(NavigationCheckpoints.goTo(new TaskState("go_to", "1,2,3,0,not a dimension"), null)
                .isEmpty());
        assertTrue(NavigationCheckpoints.goTo(new TaskState("go_to", "1,2,3,0,minecraft:the_nether,extra"), null)
                .isEmpty());
    }

    @Test
    void aMalformedWalkIsNotRestored() {
        assertTrue(
                NavigationCheckpoints.goTo(new TaskState("go_to", "1,2"), null).isEmpty());
        assertTrue(NavigationCheckpoints.goTo(new TaskState("go_to", "a,b,c"), null)
                .isEmpty());
    }

    @Test
    void anExplicitFollowStaysExplicitAndAQuietOneStaysQuiet() {
        assertEquals("loud", new FollowTask(false).saveData());
        assertEquals("quiet", new FollowTask(true).saveData());

        assertEquals(
                "loud",
                restore(NavigationCheckpoints.follow(new TaskState("follow", ""), null), FollowTask.class)
                        .saveData(),
                "a checkpoint written before the flag existed was an explicit order");
        assertEquals(
                "quiet",
                restore(NavigationCheckpoints.follow(new TaskState("follow", "quiet"), null), FollowTask.class)
                        .saveData());
    }

    @Test
    void aStayKeepsTheTicksItHadLeft() {
        assertEquals("120", new StayTask(120).saveData());

        assertEquals(
                "120",
                restore(NavigationCheckpoints.stay(new TaskState("stay", "120"), null), StayTask.class)
                        .saveData());
    }

    @Test
    void aStayWithoutUsableStateWaitsTheConfiguredTime() {
        assertEquals(
                "",
                restore(NavigationCheckpoints.stay(new TaskState("stay", ""), null), StayTask.class)
                        .saveData(),
                "an unset budget means the task timeout, not one tick");
    }

    @Test
    void aJumpAndAPortalTripAreBothRestorable() {
        assertTrue(NavigationCheckpoints.jump(new TaskState("jump", ""), null).isPresent());
        assertEquals(
                "the_nether",
                restore(
                                NavigationCheckpoints.travelThroughPortal(
                                        new TaskState("travel_through_portal", "the_nether"), null),
                                TravelThroughPortalTask.class)
                        .saveData());
    }
}
