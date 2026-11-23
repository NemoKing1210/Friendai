package dev.friendai.agent.turn;

import dev.friendai.execution.tool.Caller;
import java.util.Optional;

public interface PerceptionSource {
    /** A fresh English description of the companion's surroundings and state. */
    String describe();

    /** The description for the player the current turn acts for; sources that ignore the caller need not override. */
    default String describe(Caller caller) {
        return describe();
    }

    /**
     * The description for the player's turn, with what they pointed at when their line was accepted. Sources that
     * cannot show the referent need not override: the live description is still correct, just less specific.
     */
    default String describe(Caller caller, Optional<UtteranceReferent> referent) {
        return describe(caller);
    }
}
