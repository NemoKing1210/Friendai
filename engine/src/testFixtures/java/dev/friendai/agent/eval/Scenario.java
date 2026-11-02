package dev.friendai.agent.eval;

import java.util.List;

/**
 * One scripted conversation for the evaluation harness. Either {@code playerSays} or {@code event} starts the turn;
 * {@code replies} are the scripted model responses in order.
 *
 * <p>{@code eventKind} selects the event type: {@code world} (default) posts a {@link
 * dev.friendai.agent.turn.BrainEvent.WorldNotice}, {@code free_time} posts a shift, and {@code idle} posts the idle
 * nudge. {@code stopAfterFirstRequest} exercises interruption: the brain is cancelled before its pending response is
 * completed, so any effect it produces is a failure rather than something the fixture asserted.
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
        String language) {

    public Scenario {
        replies = replies == null ? List.of() : List.copyOf(replies);
        eventKind = eventKind == null || eventKind.isBlank() ? "world" : eventKind;
        language = language == null || language.isBlank() ? "en" : language;
    }

    /** One scripted model response: optional text plus tool calls by name. */
    public record Reply(String text, List<String> tools) {
        public Reply {
            tools = tools == null ? List.of() : List.copyOf(tools);
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
            Integer maxRepeatedToolCalls) {
        public Expectations {
            toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
            toolsNotCalled = toolsNotCalled == null ? List.of() : List.copyOf(toolsNotCalled);
            spokenContains = spokenContains == null ? List.of() : List.copyOf(spokenContains);
            spokenNotContains = spokenNotContains == null ? List.of() : List.copyOf(spokenNotContains);
        }
    }
}
