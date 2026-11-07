package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.tools.ToolGroup;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerScopeTest {
    private static final Map<String, ToolGroup> GROUPS = Map.of(
            "mine_blocks", ToolGroup.GATHERING,
            "wait", ToolGroup.BODY,
            "sit", ToolGroup.BODY,
            "find_target", ToolGroup.INFO,
            "go_to_place", ToolGroup.PLACE,
            "fly_to", ToolGroup.CONTROL,
            "interact_entity", ToolGroup.INTERACTION,
            "wander", ToolGroup.AUTONOMY);

    private static final Set<String> PLANNABLE =
            Set.of("mine_blocks", "wait", "find_target", "go_to_place", "fly_to", "interact_entity");

    @Test
    void owner_order_keeps_the_plannable_tools_of_every_group() {
        Set<String> scoped = PlannerScope.toolNames(new PlanSubject.OwnerOrder("chop"), GROUPS, PLANNABLE, Set.of());
        assertTrue(
                scoped.containsAll(Set.of("wait", "find_target", "go_to_place", "fly_to", "interact_entity")),
                "wait, find_target, go_to_place, fly_to and interact_entity are plan steps and must survive");
        assertTrue(
                scoped.contains("sit"),
                "a group with one plannable tool keeps the whole group, non-plannable members included");
    }

    @Test
    void owner_order_drops_a_group_with_no_plannable_tool() {
        assertFalse(
                PlannerScope.toolNames(new PlanSubject.OwnerOrder("chop"), GROUPS, PLANNABLE, Set.of())
                        .contains("wander"),
                "no plannable tool lives in AUTONOMY, so the group is dropped");
    }

    @Test
    void an_active_goal_keeps_every_plannable_tool() {
        assertEquals(
                Set.copyOf(GROUPS.keySet()),
                PlannerScope.toolNames(new PlanSubject.ActiveGoal(1, List.of()), GROUPS, PLANNABLE, Set.of()));
    }

    @Test
    void a_free_time_shift_keeps_every_plannable_tool() {
        assertEquals(
                Set.copyOf(GROUPS.keySet()),
                PlannerScope.toolNames(new PlanSubject.FreeTimeShift(3), GROUPS, PLANNABLE, Set.of()));
    }

    @Test
    void a_troubled_plan_keeps_every_tool_its_steps_name() {
        assertTrue(
                PlannerScope.toolNames(new PlanSubject.TroubledPlan(7, "boom"), GROUPS, PLANNABLE, Set.of("wander"))
                        .contains("wander"),
                "a tool the plan already names survives even when its group is dropped");
    }

    @Test
    void a_tool_missing_from_the_catalog_is_kept() {
        assertTrue(PlannerScope.toolNames(new PlanSubject.TroubledPlan(7, "boom"), GROUPS, PLANNABLE, Set.of("ghost"))
                .contains("ghost"));
    }
}
