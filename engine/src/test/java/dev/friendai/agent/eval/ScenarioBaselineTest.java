package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs every fixture twice, proves the two runs are identical, and writes {@code build/eval-baseline.md}.
 *
 * <p>The counters come only from what the fakes observed: gateway requests, executed tools, spoken lines and the
 * derived clarification/narration/repeat counts. Provider usage is synthetic and is labelled as such in the report.
 * Checks the headless brain cannot make are named in an external section with the command that runs them, instead of
 * being reported as a fabricated zero.
 */
class ScenarioBaselineTest {
    /** The nine scenarios the architecture's measurement contract names. */
    static final List<String> REQUIRED = List.of(
            "verified_quantity_goal",
            "partial_build",
            "remedy_then_blocker",
            "interruption",
            "reload",
            "free_time_hour",
            "silence",
            "second_language",
            "menu_agreement");

    @Test
    void everyRequiredScenarioPassesTwiceAndAgrees() throws IOException {
        List<Scenario> scenarios = ScenarioResources.loadAll();
        List<String> names = scenarios.stream().map(Scenario::name).toList();
        for (String required : REQUIRED) {
            assertTrue(names.contains(required), "missing required scenario fixture: " + required);
        }

        StringBuilder report = new StringBuilder("# Evaluation baseline\n\n");
        report.append("- revision: `")
                .append(System.getProperty("eval.revision", "working-tree"))
                .append("`\n");
        report.append("- provider usage: synthetic (FakeGateway reports zero; not provider-measured)\n");
        report.append("- determinism: every fixture ran twice and the two `Result` records matched\n\n");
        report.append("| fixture | requests | tools | spoken | clarifications | narration | repeated | silent |\n");
        report.append("|---|---:|---|---:|---:|---:|---:|---|\n");
        for (Scenario scenario : scenarios) {
            ScenarioRunner.Result first = ScenarioRunner.run(scenario);
            ScenarioRunner.Result second = ScenarioRunner.run(scenario);
            assertEquals(first, second, scenario.name() + " is not deterministic");
            assertTrue(first.passed(), scenario.name() + ": " + first.failures());
            report.append(row(scenario.name(), first)).append('\n');
        }
        report.append("\n## External checks (not scripted)\n\n");
        report.append("| check | evidence |\n|---|---|\n");
        report.append(
                "| menu state agrees with the task log | `runGameTest -Dfabric-api.gametest.filter='friendai-gametest:companion_control_tests_*'` and `memory_menu_tests_*` |\n");
        report.append(
                "| duplicate free-time shifts | `:engine:test --tests 'dev.friendai.agent.autonomy.FreeTimeServiceTest'` |\n");
        report.append(
                "| restore rejection / checkpoint bounds | `:engine:test --tests 'dev.friendai.execution.queue.QueueSaveTest'` and `runGameTest ...active_task_lifecycle_tests_*` |\n");
        report.append(
                "| a real quantity or build changed in the world | the filtered GameTest for that tool, for example `build_tests_*` |\n");

        Path out = Path.of("build", "eval-baseline.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    private static String row(String name, ScenarioRunner.Result result) {
        return "| " + name + " | " + result.requests() + " | " + String.join(",", result.toolsCalled()) + " | "
                + result.spoken().size() + " | " + result.clarifications() + " | " + result.narration() + " | "
                + result.repeatedToolCalls() + " | " + result.silent() + " |";
    }
}
