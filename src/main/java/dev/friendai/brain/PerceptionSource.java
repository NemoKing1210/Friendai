package dev.friendai.brain;

import dev.friendai.tool.Caller;

public interface PerceptionSource {
    /** A fresh English description of the companion's surroundings and state. */
    String describe();

    /** The description for the player the current turn acts for; sources that ignore the caller need not override. */
    default String describe(Caller caller) {
        return describe();
    }
}
