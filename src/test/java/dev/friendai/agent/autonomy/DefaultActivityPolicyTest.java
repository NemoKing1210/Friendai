package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.brain.AgentSettings.Autonomy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultActivityPolicyTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;
    private static final FreeTime ON =
            new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, false, 1, List.of(), List.of(), Map.of(),
                    List.of());
    private static final FreeTime ON_WITH_SHIFT =
            new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, new Shift(1, "make tools", List.of(), "", 7, 0,
                    1, false, NeedKind.STOCK), false, 2, List.of(), List.of(), Map.of(), List.of());
    private static final AutonomySettings DISABLED =
            new AutonomySettings(false, true, 5, 60, true, 20, 6, 2, 10, true, true, List.of());

    private static boolean follow(FreeTime state, AutonomySettings s, Autonomy ceiling, boolean ownerWork,
                                  boolean combat) {
        return DefaultActivityPolicy.shouldFollow(state, s, ceiling, ownerWork, combat);
    }

    @Test
    void freeTimeOffFollows() {
        assertTrue(follow(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, false));
    }

    @Test
    void disabledAutonomyFollows() {
        assertTrue(follow(ON, DISABLED, Autonomy.ASSISTIVE, false, false));
    }

    @Test
    void passiveCeilingFollows() {
        assertTrue(follow(ON, S, Autonomy.PASSIVE, false, false));
    }

    @Test
    void ownBusinessStandsStill() {
        assertFalse(follow(ON, S, Autonomy.ASSISTIVE, false, false));
        assertFalse(follow(ON, S, Autonomy.AUTONOMOUS, false, false));
    }

    @Test
    void aRunningShiftStillStands() {
        assertFalse(follow(ON_WITH_SHIFT, S, Autonomy.ASSISTIVE, false, false));
    }

    @Test
    void ownerWorkQueuedFollows() {
        assertTrue(follow(ON, S, Autonomy.ASSISTIVE, true, false));
    }

    @Test
    void combatFollows() {
        assertTrue(follow(ON, S, Autonomy.ASSISTIVE, false, true));
    }
}
