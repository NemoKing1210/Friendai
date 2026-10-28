package dev.friendai.agent.verify;

import java.util.List;

/**
 * @param success the task succeeded and every criterion the world can check holds
 * @param unmetCriteria checkable criteria that do not hold, and text-only criteria nothing can verify
 */
public record Outcome(boolean success, List<Fact> facts, List<String> unmetCriteria) {
    public Outcome {
        facts = List.copyOf(facts);
        unmetCriteria = List.copyOf(unmetCriteria);
    }

    /** The {@code facts:} suffix for an event line, or empty when there is nothing to say. */
    public String factsLine() {
        return facts.isEmpty()
                ? ""
                : "facts: " + String.join(", ", facts.stream().map(Fact::text).toList());
    }

    /** The {@code unmet:} suffix for an event line, or empty when nothing is outstanding. */
    public String unmetLine() {
        return unmetCriteria.isEmpty() ? "" : "unmet: " + String.join("; ", unmetCriteria);
    }
}
