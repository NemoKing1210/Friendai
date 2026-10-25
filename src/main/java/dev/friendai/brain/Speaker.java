package dev.friendai.brain;

import dev.friendai.text.UserMessage;
import java.util.UUID;

/** How the companion talks to the player. */
public interface Speaker {
    /** LLM-written text, spoken as is. */
    void say(String text);

    /**
     * The same reply, tagged with the player the turn was for. Speech output needs the addressee: only
     * that player hears it. Speakers that ignore the tag keep the plain {@link #say(String)}.
     */
    default void say(String text, UUID addressee) {
        say(text);
    }

    void thinking(boolean on);

    void error(UserMessage message);
}
