package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
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
}
