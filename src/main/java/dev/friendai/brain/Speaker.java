package dev.friendai.brain;

import dev.friendai.text.UserMessage;

/** How the companion talks to the player. */
public interface Speaker {
    /** LLM-written text, spoken as is. */
    void say(String text);

    void thinking(boolean on);

    void error(UserMessage message);
}
