package dev.friendai.testutil;

import dev.friendai.agent.turn.Speaker;
import dev.friendai.features.conversation.Audience;
import dev.friendai.foundation.text.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RecordingSpeaker implements Speaker {
    public final List<String> said = new ArrayList<>();
    public final List<UUID> addressees = new ArrayList<>();
    public final List<UserMessage> errors = new ArrayList<>();
    public final List<UserMessage> messages = new ArrayList<>();
    public final List<Audience> messageAudiences = new ArrayList<>();
    public final List<Boolean> thinkingLog = new ArrayList<>();
    public final List<Streamed> streamed = new ArrayList<>();
    public final List<Audience> audiences = new ArrayList<>();

    /** One streamed speech sentence and its ordering fields, as the brain sent it. */
    public record Streamed(
            long streamId,
            long sessionId,
            long sequence,
            String text,
            boolean last,
            Audience audience,
            UUID addressee) {}

    @Override
    public void say(String text) {
        said.add(text);
    }

    @Override
    public void say(String text, UUID addressee) {
        said.add(text);
        addressees.add(addressee);
    }

    @Override
    public void say(String text, Audience audience, UUID addressee) {
        said.add(text);
        audiences.add(audience);
        addressees.add(addressee);
    }

    @Override
    public void sayChat(String text, Audience audience, UUID addressee) {
        said.add(text);
        audiences.add(audience);
        addressees.add(addressee);
    }

    @Override
    public void streamSentence(
            long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {
        // The speech view and the client's stream buffer both reject this identity; a fake that accepted it would
        // hide a bug the real speaker throws on.
        if (streamId < 1 || sessionId < 1 || sequence < 0) {
            throw new IllegalArgumentException("stream identity");
        }
        streamed.add(new Streamed(streamId, sessionId, sequence, text, last, Audience.NEARBY, addressee));
    }

    @Override
    public void streamSentence(
            long streamId,
            long sessionId,
            long sequence,
            String text,
            boolean last,
            Audience audience,
            UUID addressee) {
        if (streamId < 1 || sessionId < 1 || sequence < 0) {
            throw new IllegalArgumentException("stream identity");
        }
        streamed.add(new Streamed(streamId, sessionId, sequence, text, last, audience, addressee));
    }

    @Override
    public void say(UserMessage message) {
        messages.add(message);
    }

    @Override
    public void say(UserMessage message, Audience audience, UUID addressee) {
        messages.add(message);
        messageAudiences.add(audience);
        addressees.add(addressee);
    }

    @Override
    public void thinking(boolean on) {
        thinkingLog.add(on);
    }

    @Override
    public void error(UserMessage message) {
        errors.add(message);
    }
}
