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
    private static final Map<String, ToolGroup> TOOLS = Map.of(
            "mine_blocks", ToolGroup.GATHERING,
            "mine_block_at", ToolGroup.GATHERING,
            "wait", ToolGroup.BODY,
            "go_to_place", ToolGroup.PLACE);

    private static final Set<String> ALL_TOOLS = Set.copyOf(TOOLS.keySet());

    @Test
    void a_troubled_plan_keeps_only_the_groups_its_steps_use() {
        assertEquals(
                Set.of("mine_blocks", "mine_block_at", "go_to_place"),
                PlannerScope.toolNames(new PlanSubject.TroubledPlan(7, "boom"), TOOLS, Set.of("mine_blocks")));
    }

    @Test
    void aRepairCanFixMissingMaterialsTerrainAndTravelWithoutUnlockingUnrelatedWork() {
        Map<String, ToolGroup> tools = Map.of(
                "obtain_item", ToolGroup.OBTAIN,
                "mine_blocks", ToolGroup.GATHERING,
                "clear_area", ToolGroup.BUILDING,
                "go_to", ToolGroup.MOVEMENT,
                "craft_item", ToolGroup.CRAFTING,
                "breed_animals", ToolGroup.ANIMALS);
        Set<String> names =
                PlannerScope.toolNames(new PlanSubject.TroubledPlan(7, "no space"), tools, Set.of("obtain_item"));
        assertTrue(names.containsAll(Set.of("obtain_item", "mine_blocks", "clear_area", "go_to", "craft_item")));
        assertFalse(names.contains("breed_animals"));
    }

    @Test
    void an_owner_order_keeps_every_plannable_tool() {
        assertEquals(ALL_TOOLS, PlannerScope.toolNames(new PlanSubject.OwnerOrder("chop"), TOOLS, Set.of()));
    }

    @Test
    void an_active_goal_keeps_every_plannable_tool() {
        assertEquals(ALL_TOOLS, PlannerScope.toolNames(new PlanSubject.ActiveGoal(1, List.of()), TOOLS, Set.of()));
    }

    @Test
    void a_free_time_shift_keeps_every_plannable_tool() {
        assertEquals(ALL_TOOLS, PlannerScope.toolNames(new PlanSubject.FreeTimeShift(3), TOOLS, Set.of()));
    }
}
