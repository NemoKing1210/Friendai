package dev.friendai.agent.eval;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.turn.AccessResolver;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.BrainSettings;
import dev.friendai.agent.turn.CompanionBrain;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Persona;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs a {@link Scenario} against a real {@link CompanionBrain} with scripted fakes and judges the outcome. */
public final class ScenarioRunner {
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /**
     * The completion claims {@link #narration} counts. Deliberately a short, fixed word list so the counter is
     * reproducible and reviewable; it is not a model judgement and a fixture that needs a different wording states
     * its exact text through {@code spokenNotContains}.
     */
    public static final List<String> NARRATION_MARKERS =
            List.of("done", "finished", "built", "collected", "crafted", "delivered", "completed");

    /** What happened during the run, in observable counters only. */
    public record Result(
            int requests,
            List<String> toolsCalled,
            List<String> spoken,
            int clarifications,
            int narration,
            int repeatedToolCalls,
            boolean silent,
            List<String> failures) {
        public Result {
            toolsCalled = List.copyOf(toolsCalled);
            spoken = List.copyOf(spoken);
            failures = List.copyOf(failures);
        }

        public boolean passed() {
            return failures.isEmpty();
        }
    }

    private ScenarioRunner() {}

    public static Result run(Scenario s) {
        return run(s, null);
    }

    /**
     * @param live a real gateway to answer instead of the scripted replies (the live evaluation), or null
     */
    public static Result run(Scenario s, LlmGateway live) {
        FakeGateway gateway = new FakeGateway();
        AtomicInteger liveRequests = new AtomicInteger();
        LlmGateway used = live == null
                ? gateway
                : (system, messages, specs) -> {
                    liveRequests.incrementAndGet();
                    return live.complete(system, messages, specs);
                };
        AtomicInteger ids = new AtomicInteger();
        CompletableFuture<LlmResponse> interrupted = null;
        if (s.stopAfterFirstRequest()) {
            interrupted = gateway.enqueuePending();
        } else {
            for (Scenario.Reply reply : s.replies() == null ? List.<Scenario.Reply>of() : s.replies()) {
                gateway.enqueue(responseFor(reply, ids));
            }
        }
        FakeToolExecutor tools = new FakeToolExecutor();
        RecordingSpeaker speaker = new RecordingSpeaker();
        Set<UUID> trusted = new HashSet<>();
        String access = s.access() == null ? "owner" : s.access();
        UUID speakerId = access.equals("owner") ? OWNER : OTHER;
        if (access.equals("trusted")) {
            trusted.add(OTHER);
        }
        CompanionBrain brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> used,
                tools,
                () -> "Position: 0 64 0",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, trusted::contains));
        brain.post(eventFor(s, speakerId));
        if (s.followUpEvent() != null && !s.followUpEvent().isBlank()) {
            brain.post(new BrainEvent.WorldNotice(s.followUpEvent()));
        }
        if (interrupted != null) {
            brain.cancel();
            Scenario.Reply first = s.replies().isEmpty()
                    ? new Scenario.Reply("", List.of())
                    : s.replies().getFirst();
            interrupted.complete(responseFor(first, ids));
        }
        int requests = live == null ? gateway.requests.size() : liveRequests.get();
        List<String> called = List.copyOf(tools.executed);
        List<String> spoken = List.copyOf(speaker.said);
        int clarifications = clarifications(spoken);
        int narration = narration(spoken);
        int repeated = repeatedToolCalls(called);
        List<String> failures = judge(s.expect(), requests, called, spoken, clarifications, repeated);
        if (s.stopAfterFirstRequest() && (!called.isEmpty() || !spoken.isEmpty())) {
            failures = new ArrayList<>(failures);
            failures.add("a cancelled turn must have no effect; tools=" + called + " spoken=" + spoken);
        }
        return new Result(requests, called, spoken, clarifications, narration, repeated, spoken.isEmpty(), failures);
    }

    /** Builds the gateway response for one scripted reply, numbering the tool calls so ids stay unique. */
    private static LlmResponse responseFor(Scenario.Reply reply, AtomicInteger ids) {
        List<ToolCall> calls = new ArrayList<>();
        for (String tool : reply.tools() == null ? List.<String>of() : reply.tools()) {
            calls.add(FakeGateway.call("c" + (ids.incrementAndGet()), tool));
        }
        String text = reply.text() == null ? "" : reply.text();
        return calls.isEmpty() ? FakeGateway.text(text) : FakeGateway.calls(text, calls.toArray(new ToolCall[0]));
    }

    private static BrainEvent eventFor(Scenario s, UUID speakerId) {
        if (s.playerSays() != null) {
            return new BrainEvent.PlayerSaid(speakerId, "Steve", s.playerSays(), BrainEvent.Source.CHAT);
        }
        if ("free_time".equals(s.eventKind())) {
            return new BrainEvent.FreeTimeShift(
                    List.of("Hunger is low"), "Development: stage WOOD", List.of(), List.of(), s.event());
        }
        if ("idle".equals(s.eventKind())) {
            return new BrainEvent.IdleNudge();
        }
        return new BrainEvent.WorldNotice(s.event());
    }

    /** A spoken line containing a question mark asked the owner something. */
    public static int clarifications(List<String> spoken) {
        int count = 0;
        for (String line : spoken) {
            if (line.indexOf('?') >= 0 || line.indexOf('？') >= 0) {
                count++;
            }
        }
        return count;
    }

    /** A spoken line containing one of {@link #NARRATION_MARKERS} claims that work was completed. */
    public static int narration(List<String> spoken) {
        int count = 0;
        for (String line : spoken) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String marker : NARRATION_MARKERS) {
                if (lower.contains(marker)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** How many tool executions repeated a name that had already run. */
    public static int repeatedToolCalls(List<String> called) {
        Set<String> seen = new HashSet<>();
        int repeats = 0;
        for (String tool : called) {
            if (!seen.add(tool)) {
                repeats++;
            }
        }
        return repeats;
    }

    private static List<String> judge(
            Scenario.Expectations e,
            int requests,
            List<String> called,
            List<String> spoken,
            int clarifications,
            int repeated) {
        List<String> failures = new ArrayList<>();
        if (e == null) {
            return failures;
        }
        if (e.maxRequests() != null && requests > e.maxRequests()) {
            failures.add("used " + requests + " requests, expected at most " + e.maxRequests());
        }
        for (String tool : e.toolsCalled()) {
            if (!called.contains(tool)) {
                failures.add("expected tool call " + tool + ", got " + called);
            }
        }
        for (String tool : e.toolsNotCalled()) {
            if (called.contains(tool)) {
                failures.add("tool " + tool + " must not be called");
            }
        }
        String all = String.join("\n", spoken);
        for (String part : e.spokenContains()) {
            if (!all.contains(part)) {
                failures.add("spoken text lacks \"" + part + "\": " + spoken);
            }
        }
        for (String part : e.spokenNotContains()) {
            if (all.contains(part)) {
                failures.add("spoken text claims \"" + part + "\": " + spoken);
            }
        }
        if (Boolean.TRUE.equals(e.nothingSpoken()) && !spoken.isEmpty()) {
            failures.add("expected silence, spoke " + spoken);
        }
        if (e.maxClarifications() != null && clarifications > e.maxClarifications()) {
            failures.add(
                    "asked " + clarifications + " clarifying question(s), expected at most " + e.maxClarifications());
        }
        if (e.maxRepeatedToolCalls() != null && repeated > e.maxRepeatedToolCalls()) {
            failures.add("repeated a tool " + repeated + " time(s), expected at most " + e.maxRepeatedToolCalls());
        }
        return failures;
    }
}
