package dev.friendai.agent.recover;

import java.util.List;

/**
 * @param detail a short plain description of the cause, safe to store as a lesson
 * @param remedies things not yet tried, phrased as tool hints for the model
 * @param needsPlayer true when nothing the companion can do alone is left
 */
public record Diagnosis(FailureKind kind, String detail, List<String> remedies, boolean needsPlayer) {
    public Diagnosis {
        remedies = List.copyOf(remedies);
    }

    /** The {@code diagnosis:} suffix of an event line. */
    public String line() {
        StringBuilder out =
                new StringBuilder("diagnosis: ").append(kind.name()).append(' ').append(detail);
        if (!remedies.isEmpty()) {
            out.append("; try: ").append(String.join(" or ", remedies));
        }
        if (needsPlayer) {
            out.append("; ask the player for help");
        }
        return out.toString();
    }
}
