package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.tools.ToolGroup;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerScopeTest {
    private static final Map<String, ToolGroup> GROUPS =
            Map.of("mine_blocks", ToolGroup.GATHERING, "look_around", ToolGroup.INFO, "sit", ToolGroup.BODY);

    private static final Map<String, ToolGroup> WITH_PLACE =
            Map.of("mine_blocks", ToolGroup.GATHERING, "go_to_place", ToolGroup.PLACE, "sit", ToolGroup.BODY);

    @Test
    void owner_order_drops_the_non_planning_groups() {
        assertEquals(
                Set.of("mine_blocks"), PlannerScope.toolNames(new PlanSubject.OwnerOrder("dig"), GROUPS, Set.of()));
    }

    @Test
    void owner_order_drops_the_place_group() {
        assertEquals(
                Set.of("mine_blocks"), PlannerScope.toolNames(new PlanSubject.OwnerOrder("dig"), WITH_PLACE, Set.of()));
    }

    @Test
    void an_active_goal_keeps_every_plannable_tool() {
        assertEquals(
                Set.of("mine_blocks", "look_around", "sit"),
                PlannerScope.toolNames(new PlanSubject.ActiveGoal(1, List.of()), GROUPS, Set.of()));
    }

    @Test
    void a_free_time_shift_keeps_every_plannable_tool() {
        assertEquals(
                Set.of("mine_blocks", "look_around", "sit"),
                PlannerScope.toolNames(new PlanSubject.FreeTimeShift(3), GROUPS, Set.of()));
    }

    @Test
    void a_troubled_plan_keeps_every_tool_its_steps_name() {
        assertEquals(
                Set.of("mine_blocks", "go_to_place"),
                PlannerScope.toolNames(new PlanSubject.TroubledPlan(7, "boom"), WITH_PLACE, Set.of("go_to_place")));
    }

    @Test
    void a_tool_missing_from_the_catalog_is_kept() {
        assertEquals(
                Set.of("mine_blocks", "ghost"),
                PlannerScope.toolNames(new PlanSubject.TroubledPlan(7, "boom"), GROUPS, Set.of("ghost")));
    }
}
