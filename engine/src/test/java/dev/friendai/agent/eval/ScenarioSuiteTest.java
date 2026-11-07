package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs every {@code scenarios/*.json} resource against the brain with scripted fakes. */
class ScenarioSuiteTest {
    @TestFactory
    Stream<DynamicTest> allScenariosPass() throws IOException {
        var scenarios = ScenarioResources.loadAll();
        assertTrue(!scenarios.isEmpty(), "no scenarios found");
        return scenarios.stream()
                .map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> {
                    ScenarioRunner.Result result = ScenarioRunner.run(scenario);
                    assertTrue(result.passed(), scenario.name() + ": " + result.failures());
                }));
    }

    /**
     * The 2026-11-07 regression: a {@code set_goal} followed by an invalid {@code queue_add} must let the model see
     * the error and correct the call itself. Zero planner consultations also mean no failed planner attempt, so the
     * durable log has no {@code plan.failed} record for this turn.
     */
    @Test
    void anInvalidQueueAddArgumentDoesNotConsultThePlanner() throws IOException {
        Scenario scenario = ScenarioResources.loadAll().stream()
                .filter(candidate -> candidate.name().equals("invalid_queue_add_args"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("invalid_queue_add_args.json is not registered"));
        ScenarioRunner.Result result = ScenarioRunner.run(scenario);
        assertTrue(result.passed(), scenario.name() + ": " + result.failures());
        assertEquals(0, result.plannerConsultations(), "the failed queue_add must not reach the planner");
        assertEquals(0, result.plansApplied(), "the planner queued no plan");
        assertEquals(
                2,
                result.toolsCalled().stream().filter("queue_add"::equals).count(),
                "the invalid call and the corrected one");
    }

    /**
     * The 2026-11-07 throughput and mining regressions are fixtures, so a file that is not in {@code index.txt} would
     * never run: this test fails before the scenario does.
     */
    @Test
    void theThroughputRegressionFixturesAreRegistered() throws IOException {
        var names = ScenarioResources.loadAll().stream().map(Scenario::name).toList();
        for (String fixture : List.of("stone_tools_from_forest", "cobblestone_station_trap", "unreachable_tree_top")) {
            assertTrue(names.contains(fixture), fixture + ".json is missing from scenarios/index.txt");
        }
    }
}
