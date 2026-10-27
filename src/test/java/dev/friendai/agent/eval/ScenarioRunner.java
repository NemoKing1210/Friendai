package dev.friendai.agent.eval;

import dev.friendai.brain.AccessResolver;
import dev.friendai.brain.BrainEvent;
import dev.friendai.brain.BrainSettings;
import dev.friendai.brain.CompanionBrain;
import dev.friendai.brain.LlmGateway;
import dev.friendai.brain.Persona;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs a {@link Scenario} against a real {@link CompanionBrain} with scripted fakes and judges the outcome. */
public final class ScenarioRunner {
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** What happened during the run. */
    public record Result(int requests, List<String> toolsCalled, List<String> spoken, List<String> failures) {
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
        LlmGateway used = live == null ? gateway : (system, messages, specs) -> {
            liveRequests.incrementAndGet();
            return live.complete(system, messages, specs);
        };
        int n = 0;
        for (Scenario.Reply reply : s.replies()) {
            List<ToolCall> calls = new ArrayList<>();
            for (String tool : reply.tools() == null ? List.<String>of() : reply.tools()) {
                calls.add(FakeGateway.call("c" + (++n), tool));
            }
            String text = reply.text() == null ? "" : reply.text();
            gateway.enqueue(calls.isEmpty()
                    ? FakeGateway.text(text)
                    : FakeGateway.calls(text, calls.toArray(new ToolCall[0])));
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
        if (s.playerSays() != null) {
            brain.post(new BrainEvent.PlayerSaid(speakerId, "Steve", s.playerSays(), BrainEvent.Source.CHAT));
        } else {
            brain.post(new BrainEvent.WorldNotice(s.event()));
        }
        int requests = live == null ? gateway.requests.size() : liveRequests.get();
        List<String> failures = judge(s.expect(), requests, tools.executed, speaker.said);
        return new Result(requests, List.copyOf(tools.executed), List.copyOf(speaker.said), failures);
    }

    private static List<String> judge(
            Scenario.Expectations e, int requests, List<String> called, List<String> spoken) {
        List<String> failures = new ArrayList<>();
        if (e == null) {
            return failures;
        }
        if (e.maxRequests() != null && requests > e.maxRequests()) {
            failures.add("used " + requests + " requests, expected at most " + e.maxRequests());
        }
        for (String tool : e.toolsCalled() == null ? List.<String>of() : e.toolsCalled()) {
            if (!called.contains(tool)) {
                failures.add("expected tool call " + tool + ", got " + called);
            }
        }
        for (String tool : e.toolsNotCalled() == null ? List.<String>of() : e.toolsNotCalled()) {
            if (called.contains(tool)) {
                failures.add("tool " + tool + " must not be called");
            }
        }
        String all = String.join("\n", spoken);
        for (String part : e.spokenContains() == null ? List.<String>of() : e.spokenContains()) {
            if (!all.contains(part)) {
                failures.add("spoken text lacks \"" + part + "\": " + spoken);
            }
        }
        if (Boolean.TRUE.equals(e.nothingSpoken()) && !spoken.isEmpty()) {
            failures.add("expected silence, spoke " + spoken);
        }
        return failures;
    }
}
