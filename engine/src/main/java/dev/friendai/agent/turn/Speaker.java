package dev.friendai.agent.turn;

import dev.friendai.features.conversation.Audience;
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
     * The same reply, tagged with who is meant to hear it. The addressee is the player the turn answers; it
     * matters only for {@link Audience#PRIVATE}.
     */
    default void say(String text, Audience audience, UUID addressee) {
        say(text, addressee);
    }

    /** The chat line alone: a streamed turn has already spoken its sentences through {@link #streamSentence}. */
    default void sayChat(String text, Audience audience, UUID addressee) {
        say(text, audience, addressee);
    }

    /**
     * One ordered sentence of a streamed reply. The default speaker has no stream transport and ignores it; a
     * speaker that sends speech separately from chat uses {@code streamId/sessionId/sequence} for ordering.
     */
    default void streamSentence(
            long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {}

    /** One ordered sentence with its intended audience. */
    default void streamSentence(
            long streamId,
            long sessionId,
            long sequence,
            String text,
            boolean last,
            Audience audience,
            UUID addressee) {
        streamSentence(streamId, sessionId, sequence, text, last, addressee);
    }

    /**
     * A line the mod composed itself, as a translation key with arguments, spoken like a reply. It exists so a
     * turn that the model ends silently can still tell the owner what happened.
     */
    void say(UserMessage message);

    /** The same composed line, delivered to the audience the turn belongs to. */
    default void say(UserMessage message, Audience audience, UUID addressee) {
        say(message);
    }

    void thinking(boolean on);

    void error(UserMessage message);
}
