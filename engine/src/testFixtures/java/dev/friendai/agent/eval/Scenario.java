package dev.friendai.agent.eval;

import com.google.gson.JsonArray;
import dev.friendai.agent.turn.AgentSettings;
import dev.friendai.agent.turn.AgentSettings.Autonomy;
import java.util.List;

/**
 * One scripted conversation for the evaluation harness. Either {@code playerSays} or {@code event} starts the turn;
 * {@code replies} are the scripted model responses in order.
 *
 * <p>{@code eventKind} selects the event type: {@code world} (default) posts a {@link
 * dev.friendai.agent.turn.BrainEvent.WorldNotice}, {@code free_time} posts a shift, and {@code idle} posts the idle
 * nudge. {@code stopAfterFirstRequest} exercises interruption: the brain is cancelled before its pending response is
 * completed, so any effect it produces is a failure rather than something the fixture asserted.
 *
 * <p>{@code autonomy} (the same case-insensitive words the server config uses: {@code passive}, {@code assistive},
 * {@code autonomous}) and {@code promptBudget} are the settings the real server supplies through {@link
 * AgentSettings}. They default to {@code assistive} and {@code AgentSettings.DEFAULTS.promptBudget()} so a fixture
 * written before the baseline existed behaves exactly as it did. The runner also applies the autonomy ceiling where
 * the real drivers do: a passive companion starts no free time and only an autonomous one posts the idle nudge, so an
 * explicit level changes the measured run instead of decorating it.
 *
 * <p>{@code simulatedTicks} is how many 50 ms virtual scheduler ticks (20 ticks per second, one simulated hour =
 * 72,000) are advanced after the scripted events. Absent means the clock is not advanced.
 *
 * <p>{@code plans} scripts what the planner proposes, in order; {@code plannerFailure} makes every proposal fail with
 * that {@link dev.friendai.agent.plan.PlanFailure.Code} name instead. Both absent means "no planner", so a fixture
 * written before the port was scripted behaves exactly as it did: the brain falls back to planning itself.
 *
 * <p>{@code eventKind} {@code plan_blocked} posts a {@link dev.friendai.agent.turn.BrainEvent.PlanBlocked} for
 * {@code planId} whose reason is {@code event}, which is how a fixture exercises a failed plan with no goal.
 */
public record Scenario(
        String name,
        String access,
        String playerSays,
        String event,
        String eventKind,
        String followUpEvent,
        List<Reply> replies,
        Expectations expect,
        boolean stopAfterFirstRequest,
        String language,
        String autonomy,
        Integer promptBudget,
        Integer simulatedTicks,
        List<Plan> plans,
        String plannerFailure,
        Integer planId) {

    /** One simulated second of the virtual clock, in the ticks and milliseconds the scheduler uses. */
    public static final int TICKS_PER_SECOND = 20;

    public static final long MILLIS_PER_TICK = 50L;

    public Scenario {
        replies = replies == null ? List.of() : List.copyOf(replies);
        plans = plans == null ? List.of() : List.copyOf(plans);
        eventKind = eventKind == null || eventKind.isBlank() ? "world" : eventKind;
        language = language == null || language.isBlank() ? "en" : language;
        autonomy = autonomy == null || autonomy.isBlank() ? "assistive" : autonomy.strip();
        if (simulatedTicks != null && simulatedTicks < 0) {
            throw new IllegalArgumentException("simulatedTicks must not be negative in " + name);
        }
    }

    /** The autonomy ceiling, parsed from the same case-insensitive words the server config accepts. */
    public Autonomy autonomyLevel() {
        return Autonomy.parse(autonomy);
    }

    /** The effective prompt budget: the fixture's value, or the shipped default when it does not name one. */
    public int effectivePromptBudget() {
        return promptBudget == null ? AgentSettings.DEFAULTS.promptBudget() : promptBudget;
    }

    /** One scripted model response: optional text plus tool calls by name. */
    public record Reply(String text, List<String> tools) {
        public Reply {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /**
     * One scripted planner answer. {@code steps} use the same node shape the planner's own prompt asks for
     * ({@code {"tool": ..., "args": {...}}}, conditionals and repeats); a step's {@code args} object is accepted here
     * because the fixture is data, and the runner normalizes it to the object string the queue parser expects.
     */
    public record Plan(String goal, String rationale, String expected, JsonArray steps) {
        public Plan {
            goal = goal == null ? "" : goal;
            rationale = rationale == null ? "" : rationale;
            expected = expected == null ? "" : expected;
            steps = steps == null ? new JsonArray() : steps.deepCopy();
        }

        @Override
        public JsonArray steps() {
            return steps.deepCopy();
        }
    }

    /** What the run must show. Absent fields are not checked. */
    public record Expectations(
            Integer maxRequests,
            List<String> toolsCalled,
            List<String> toolsNotCalled,
            List<String> spokenContains,
            List<String> spokenNotContains,
            Boolean nothingSpoken,
            Integer maxClarifications,
            Integer maxRepeatedToolCalls,
            Integer plannerConsulted,
            Integer plansApplied) {
        public Expectations {
            toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
            toolsNotCalled = toolsNotCalled == null ? List.of() : List.copyOf(toolsNotCalled);
            spokenContains = spokenContains == null ? List.of() : List.copyOf(spokenContains);
            spokenNotContains = spokenNotContains == null ? List.of() : List.copyOf(spokenNotContains);
        }
    }
}
