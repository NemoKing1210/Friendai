package dev.friendai.agent.eval;

import java.util.List;

/**
 * One scripted conversation for the evaluation harness. Either {@code playerSays} or {@code event} starts the turn;
 * {@code replies} are the scripted model responses in order.
 */
public record Scenario(
        String name, String access, String playerSays, String event, List<Reply> replies, Expectations expect) {

    /** One scripted model response: optional text plus tool calls by name. */
    public record Reply(String text, List<String> tools) {}

    /** What the run must show. Absent fields are not checked. */
    public record Expectations(
            Integer maxRequests,
            List<String> toolsCalled,
            List<String> toolsNotCalled,
            List<String> spokenContains,
            Boolean nothingSpoken) {}
}
