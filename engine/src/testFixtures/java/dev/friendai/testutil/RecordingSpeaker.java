package dev.friendai.testutil;

import dev.friendai.agent.turn.Speaker;
import dev.friendai.foundation.text.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RecordingSpeaker implements Speaker {
    public final List<String> said = new ArrayList<>();
    public final List<UUID> addressees = new ArrayList<>();
    public final List<UserMessage> errors = new ArrayList<>();
    public final List<UserMessage> messages = new ArrayList<>();
    public final List<Boolean> thinkingLog = new ArrayList<>();
    public final List<Streamed> streamed = new ArrayList<>();

    /** One streamed speech sentence and its ordering fields, as the brain sent it. */
    public record Streamed(long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {}

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
    public void streamSentence(
            long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {
        streamed.add(new Streamed(streamId, sessionId, sequence, text, last, addressee));
    }

    @Override
    public void say(UserMessage message) {
        messages.add(message);
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
