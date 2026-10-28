package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.core.FreeTimeDriver;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The free-time rails: no player attacks, no owner-following, no owner-item moves or command execution. */
class FreeTimeRailsTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;

    @Test
    void theDenyListCoversPlayerHarmAndOwnerMoves() {
        assertTrue(FreeTimeDriver.DENIED.contains("attack_player"));
        assertTrue(FreeTimeDriver.DENIED.contains("follow_player"));
        assertTrue(FreeTimeDriver.DENIED.contains("execute_command"));
        assertTrue(FreeTimeDriver.DENIED.contains("give_items"));
        assertTrue(FreeTimeDriver.DENIED.contains("drop_items"));
        assertFalse(FreeTimeDriver.DENIED.contains("attack"), "hostile mobs stay a legitimate hunt");
        assertFalse(FreeTimeDriver.DENIED.contains("deposit_items"), "storing loot is the point of free time");
    }

    @Test
    void noTemplateEverFollowsTheOwner() {
        for (NeedKind kind : List.of(NeedKind.SHELTER, NeedKind.SURVIVE, NeedKind.SLEEP, NeedKind.UNLOAD,
                NeedKind.COLLECT, NeedKind.HUNGER, NeedKind.FARM_CREATE)) {
            var steps = ReflexPlans.plan(new Need(kind, 50, Executor.REFLEX, ""),
                    Facts.base().home("home", 40).build(), S);
            steps.ifPresent(list -> assertFalse(tools(list).contains("follow_player"),
                    kind + " must not follow the owner: " + tools(list)));
        }
    }

    private static List<String> tools(List<ReflexStep> steps) {
        return steps.stream().map(ReflexStep::tool).toList();
    }
}
