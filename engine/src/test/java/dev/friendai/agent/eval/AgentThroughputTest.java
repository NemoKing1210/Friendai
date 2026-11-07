package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the 2026-11-07 throughput regressions across whole turns: how often the brain asks the scripted planner, how
 * many model requests a turn costs and which tools it chose. The fixtures script the model and the tool answers, so
 * these runs measure the brain's turn machinery, never a provider, and no assertion here reads a reply or a tool
 * result: the fixture authored those texts itself, so only the observed counters can turn a product regression red.
 */
class AgentThroughputTest {

    @Test
    void a_stone_tools_order_runs_three_model_steps_without_the_planner() throws IOException {
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
    }

    @Test
    void a_rejected_plan_is_repaired_in_the_same_turn() throws IOException {
        Outcome result = run("invalid_queue_add_args");
        assertEquals(0, result.plannerCalls(), "a rejected queue_add never reaches the planner");
        assertEquals(2, result.count("queue_add"), "the invalid call and the corrected one");
        assertEquals(4, result.requests(), "every model step of the repair turn was requested");
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
    }
}
