package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the 2026-11-07 throughput and mining regressions across whole turns: how often the brain asks the scripted
 * planner, how many model requests a turn costs, which tools the model chose and what the owner hears. The fixtures
 * script the model and the tool answers, so these runs measure the brain's turn machinery, never a provider.
 *
 * <p>A scripted tool error is observable through its consequence, not through a counter the runner exposes: the turn
 * must go on with the next model step instead of failing, stopping or re-planning, which is what the request and call
 * sequences below prove.
 */
class AgentThroughputTest {

    @Test
    void a_stone_tools_order_plans_with_the_model_and_never_names_a_stonecutter() throws IOException {
        Outcome result = run("stone_tools_from_forest");
        assertEquals(
                0,
                result.plannerCalls(),
                "a set_goal followed by a failed tool call must not reach the planner; the model repairs itself");
        assertEquals(3, result.requests(), "one request per model step: mine, obtain, reply");
        assertEquals(
                List.of("set_goal", "mine_blocks", "obtain_items"),
                result.toolCalls(),
                "a goal, then mine, then obtain");
        assertTrue(
                result.toolCalls().stream().noneMatch(name -> name.startsWith("craft")),
                "a mineable target must not be routed through a craft: " + result.toolCalls());
        assertEquals(1, result.spoken().size());
        assertTrue(
                result.spoken().getFirst().contains("two sets of tools"),
                result.spoken().toString());
    }

    @Test
    void a_rejected_plan_is_repaired_in_the_same_turn() throws IOException {
        Outcome result = run("invalid_queue_add_args");
        assertEquals(0, result.plannerCalls(), "a rejected queue_add never reaches the planner");
        assertEquals(2, result.count("queue_add"), "the invalid call and the corrected one");
        assertEquals(4, result.requests(), "every model step of the repair turn was requested");
    }

    @Test
    void a_cobblestone_order_is_answered_with_a_mine_not_a_station() throws IOException {
        Outcome result = run("cobblestone_station_trap");
        assertEquals(0, result.plannerCalls(), "an ordinary obtain call never reaches the planner");
        assertEquals(List.of("obtain_items"), result.toolCalls());
        assertTrue(
                result.toolCalls().stream().noneMatch(name -> name.startsWith("craft")),
                "a mineable block is mined, not crafted through a station: " + result.toolCalls());
        assertTrue(
                result.spoken().stream().noneMatch(line -> line.contains("stonecutter")),
                "the owner is never told a station is needed: " + result.spoken());
        assertEquals(2, result.requests(), "the call plus the answer");
    }

    @Test
    void a_partial_fell_continues_and_stays_unmet() throws IOException {
        Outcome result = run("unreachable_tree_top");
        assertEquals(0, result.plannerCalls(), "an ordinary felling order never reaches the planner");
        assertEquals(3, result.requests(), "the shortfall does not end the turn");
        assertEquals(List.of("fell_tree", "obtain_item"), result.toolCalls(), "the next step follows the shortfall");
        assertEquals(0, result.narration(), "a partial felling is not reported as completion");
        assertTrue(
                result.spoken().getFirst().contains("8 of 9"), result.spoken().toString());
        assertTrue(
                result.spoken().getFirst().contains("unreachable"),
                result.spoken().toString());
    }

    private static Outcome run(String name) throws IOException {
        Scenario scenario = ScenarioResources.loadAll().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + ".json is not registered in scenarios/index.txt"));
        ScenarioRunner.Result result = ScenarioRunner.run(scenario);
        assertTrue(result.passed(), name + ": " + result.failures());
        return new Outcome(result);
    }

    /** The counters one fixture run produced, named the way this test reads them. */
    private record Outcome(ScenarioRunner.Result result) {
        int plannerCalls() {
            return result.plannerConsultations();
        }

        int requests() {
            return result.requests();
        }

        List<String> toolCalls() {
            return result.toolsCalled();
        }

        long count(String tool) {
            return result.toolsCalled().stream().filter(tool::equals).count();
        }

        List<String> spoken() {
            return result.spoken();
        }

        int narration() {
            return result.narration();
        }
    }
}
