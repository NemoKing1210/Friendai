package dev.friendai.agent.goal;

import dev.friendai.execution.queue.Guard;
import java.util.Optional;

/**
 * One thing that must be true for a goal to be done. With a {@code check} the world decides; without one the
 * criterion stays unmet until the goal is finished by hand.
 */
public record Criterion(String text, Optional<Guard> check) {
    public Criterion {
        check = check == null ? Optional.empty() : check;
    }
}
