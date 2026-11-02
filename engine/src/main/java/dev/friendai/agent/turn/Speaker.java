package dev.friendai.agent.turn;

import dev.friendai.foundation.text.UserMessage;
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

    /**
     * One ordered sentence of a streamed reply. The default speaker has no stream transport and ignores it; a
     * speaker that sends speech separately from chat uses {@code streamId/sessionId/sequence} for ordering.
     */
    default void streamSentence(
            long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {}

    void thinking(boolean on);

    void error(UserMessage message);
}
