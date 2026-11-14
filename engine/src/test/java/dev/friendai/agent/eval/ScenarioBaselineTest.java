package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs every fixture twice, proves the two runs are identical, and writes {@code build/eval-baseline.md}.
 *
 * <p>The counters come only from what the fakes observed: gateway requests and their payload metrics, executed tools,
 * spoken lines and the derived clarification/narration/repeat counts. Provider usage is synthetic and is labelled as
 * such in the report; an absent counter is printed as {@code unknown}, never as a fabricated zero. Checks the headless
 * brain cannot make are named in an external section with the command that runs them, instead of being reported as a
 * measured pass.
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

    /** The baseline additions: short action, eight-step objective, repairable failure and an hour per autonomy level. */
    static final List<String> BASELINE_ADDITIONS = List.of(
            "short_action",
            "eight_step_objective",
            "repairable_failure",
            "hour_passive",
            "hour_assistive",
            "hour_autonomous",
            "planning_first_order",
            "recovery_without_goal",
            "planner_transport_failure_falls_back",
            "stone_tools_from_forest",
            "casual_chat_en",
            "casual_chat_ru",
            "delayed_provider");

    private static final Duration RUN_BOUND = Duration.ofSeconds(30);

    @Test
    void everyRequiredScenarioPassesTwiceAndAgrees() throws IOException {
        List<Scenario> scenarios = ScenarioResources.loadAll();
        List<String> names = scenarios.stream().map(Scenario::name).toList();
        for (String required : REQUIRED) {
            assertTrue(names.contains(required), "missing required scenario fixture: " + required);
        }
        for (String addition : BASELINE_ADDITIONS) {
            assertTrue(names.contains(addition), "missing baseline scenario fixture: " + addition);
        }

        StringBuilder report = new StringBuilder("# Evaluation baseline\n\n");
        report.append("- revision: `")
                .append(System.getProperty("eval.revision", "working-tree"))
                .append("`\n");
        report.append("- environment: Java ")
                .append(System.getProperty("java.version", "unknown"))
                .append(" on ")
                .append(System.getProperty("os.name", "unknown"))
                .append(' ')
                .append(System.getProperty("os.arch", "unknown"))
                .append('\n');
        report.append("- provider usage: synthetic (FakeGateway answers with `Usage(0, 0)`; not provider spend)\n");
        report.append("- determinism: every fixture ran twice and the two `Result` records and table rows matched\n\n");
        report.append("## Metric definitions\n\n");
        report.append("- `request`: one `LlmGateway.complete` or `completeJson` call recorded by `FakeGateway`, counted"
                + " once.\n");
        report.append("- `system chars`: Java UTF-16 `String.length()` of the system prompt, summed over requests.\n");
        report.append(
                "- `history msgs` / `history chars`: `Message` count, and the sum of message text plus tool name, raw"
                        + " arguments and signature lengths, summed over requests.\n");
        report.append("- `tools` / `tool bytes`: `ToolSpec` count, and UTF-8 bytes of each spec's name + description +"
                + " schema JSON, summed over requests.\n");
        report.append(
                "- `ticks`: 50 ms virtual `ManualScheduler` ticks advanced after the scripted events (20 per simulated"
                        + " second; 72,000 per simulated hour); no wall clock or real pause is involved.\n");
        report.append("- `retries` / `errors` / `cancelled`: requests issued after a failed or cancelled completion;"
                + " completions that failed with an error; turn outcomes reported as `cancelled`. Live runs cannot"
                + " observe provider-internal retries, so those print `unknown`.\n");
        report.append(
                "- `usage`: the `Usage` envelope of completed responses, counted once per response (never once per"
                        + " streamed delta); `synthetic` usage is the scripted zero and is never provider spend.\n");
        report.append(
                "- `outcome`: `pass`/`fail` from the fixture expectations, followed by the turn outcomes the brain"
                        + " reported (`delivered`, `error`, `cancelled`, or a dropped event).\n");
        report.append("- `provenance`: `scripted FakeGateway` for this test; `live provider` only when a live gateway"
                + " answers.\n");
        report.append("- `planner/plans`: times the brain asked the scripted planner for its input, and proposals it"
                + " applied to the queue; a fixture with no scripted planner asks and applies none.\n");
        report.append("- `timeline`: virtual milliseconds from the posted event to the first provider request, the"
                + " summed scripted provider delay, the first tool call, the first spoken line, and (interruption"
                + " fixtures only) stop to cancellation. `-` means the stage never happened. Provider delay is the"
                + " only non-zero stage a fake can produce: in-process work and world work take the same virtual"
                + " instant, and a real client's numbers come from `turn.start`/`turn.end`, `llm.response` and"
                + " `task.cancel` durations in the durable log.\n");
        report.append("- `unknown`: the metric was not observed, for example payload fields in a run that made no"
                + " request.\n\n");
        report.append(
                "| fixture | scenario | autonomy | budget | requests | system chars | history msgs | history chars |"
                        + " tools | tool bytes | ticks | planner/plans | usage | retries/errors/cancelled | timeline |"
                        + " outcome | provenance |\n");
        report.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|---|\n");
        for (Scenario scenario : scenarios) {
            ScenarioRunner.Result first = runBounded(scenario);
            ScenarioRunner.Result second = runBounded(scenario);
            assertEquals(first, second, scenario.name() + " is not deterministic");
            assertEquals(
                    row(scenario, first),
                    row(scenario, second),
                    scenario.name() + " printed different metrics on its two runs");
            assertTrue(first.passed(), scenario.name() + ": " + first.failures());
            if ("delayed_provider".equals(scenario.name())) {
                assertTrue(first.timeline().providerMs() >= 500, "the scripted provider delay must be visible");
                assertTrue(first.timeline().eventToFirstSpeechMs() >= 500, "speech cannot precede the provider answer");
            }
            if (scenario.stopAfterFirstRequest()) {
                assertNotNull(
                        first.timeline().stopToCancelMs(), "an interruption fixture must record stop to cancellation");
            }
            report.append(row(scenario, first)).append('\n');
        }
        report.append("\n## External checks (not scripted)\n\n");
        report.append("| check | evidence |\n|---|---|\n");
        report.append("| menu state agrees with the task log | `runGameTest"
                + " -Dfabric-api.gametest.filter='friendai-gametest:companion_control_tests_*'` and"
                + " `memory_menu_tests_*` |\n");
        report.append("| duplicate free-time shifts | `:engine:test --tests"
                + " 'dev.friendai.agent.autonomy.FreeTimeServiceTest'` |\n");
        report.append("| restore rejection / checkpoint bounds | `:engine:test --tests"
                + " 'dev.friendai.execution.queue.QueueSaveTest'` and `runGameTest"
                + " ...active_task_lifecycle_tests_*` |\n");
        report.append(
                "| a real quantity or build changed in the world | the filtered GameTest for that tool, for example"
                        + " `build_tests_*` |\n");

        report.append("\n## What the matrix separates\n\n");
        report.append("| stage | scripted run | real session evidence |\n|---|---|---|\n");
        report.append("| provider delay | `timeline`'s provider column, scripted by `delayMs` | `llm.response`"
                + " `durationMs`, and `llm.attempt` per-attempt timings |\n");
        report.append("| server work | zero by construction: the fakes answer in the same virtual instant |"
                + " `turn.end` `durationMs` minus the summed `llm.response` durations; `clock.gap` for a >1 s"
                + " server stall |\n");
        report.append("| intentional wait | a fixture can script a declared wait only through the real task runner"
                + " | `task.progress`/`task.phase` with `reason=waiting`, from `Task.waitingTicks()` |\n");
        report.append("| actual stall | not reachable headlessly | `task.snapshot` with `reason=stalled`, then"
                + " `task.finish` `failed` and `longestNoProgressTicks` |\n");
        report.append("| stop to cancellation | `interruption`'s stop→cancel column | `task.cancel` `durationMs`"
                + " plus the last mutation before it |\n");
        report.append("\nMovement through obstructed terrain, real inventory mutations and naturalness cannot be"
                + " judged here: the harness has fake tools and a fixed position. Those stay with the filtered"
                + " GameTests named below and the opt-in live runs.\n");

        Path out = Path.of("build", "eval-baseline.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    /** Runs one fixture in a bounded worker so a hang names the fixture instead of stalling the suite. */
    private static ScenarioRunner.Result runBounded(Scenario scenario) {
        return assertTimeoutPreemptively(
                RUN_BOUND,
                () -> ScenarioRunner.run(scenario),
                scenario.name() + " exceeded the " + RUN_BOUND.toSeconds() + " s harness bound");
    }

    private static String row(Scenario scenario, ScenarioRunner.Result result) {
        return "| " + scenario.name() + " | " + describe(scenario) + " | " + result.autonomy() + " | "
                + result.promptBudget() + " | " + result.requests() + " | " + value(result.systemChars()) + " | "
                + value(result.historyMessages()) + " | " + value(result.historyChars()) + " | "
                + value(result.toolSpecs()) + " | " + value(result.toolSpecBytes()) + " | "
                + value(result.simulatedTicks()) + " | " + result.plannerConsultations() + "/"
                + result.plansApplied() + " | " + usage(result) + " | " + value(result.retries()) + "/"
                + value(result.errors()) + "/" + value(result.cancelledRequests()) + " | "
                + timeline(result.timeline()) + " | " + outcome(result) + " | " + result.provenance() + " |";
    }

    /** `event→request / provider / event→tool / event→speech / stop→cancel`, each `-` when it never happened. */
    private static String timeline(ScenarioRunner.Timeline timeline) {
        if (timeline == null) {
            return "-";
        }
        return value(timeline.eventToFirstRequestMs()) + "/" + value(timeline.providerMs()) + "/"
                + value(timeline.eventToFirstToolMs()) + "/" + value(timeline.eventToFirstSpeechMs()) + "/"
                + value(timeline.stopToCancelMs());
    }

    /** A short, single-line statement of what the fixture feeds the brain. */
    private static String describe(Scenario scenario) {
        String text;
        if (scenario.playerSays() != null) {
            text = "player: " + scenario.playerSays();
        } else if ("free_time".equals(scenario.eventKind())) {
            text = "free_time: " + scenario.event();
        } else if ("idle".equals(scenario.eventKind())) {
            text = "idle nudge";
        } else {
            text = "event: " + scenario.event();
        }
        text = text.replace('|', '/').replace('\n', ' ');
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    private static String outcome(ScenarioRunner.Result result) {
        String observed = result.outcomes().isEmpty() ? "none" : String.join(", ", result.outcomes());
        return result.outcome() + " (" + observed.replace('|', '/') + ")";
    }

    private static String usage(ScenarioRunner.Result result) {
        if (result.usageInputTokens() == null || result.usageOutputTokens() == null) {
            return "unknown";
        }
        return result.usageInputTokens() + "/" + result.usageOutputTokens()
                + (result.syntheticUsage() ? " synthetic" : " provider")
                + " (" + result.usageSources() + " replies)";
    }

    /** An absent counter is unknown, never a fabricated zero. */
    private static String value(Integer number) {
        return number == null ? "unknown" : Integer.toString(number);
    }
}
