package dev.friendai.testutil;

import dev.friendai.brain.Speaker;
import dev.friendai.text.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RecordingSpeaker implements Speaker {
    public final List<String> said = new ArrayList<>();
    public final List<UUID> addressees = new ArrayList<>();
    public final List<UserMessage> errors = new ArrayList<>();
    public final List<Boolean> thinkingLog = new ArrayList<>();

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
    public void thinking(boolean on) {
        thinkingLog.add(on);
    }

    @Override
    public void error(UserMessage message) {
        errors.add(message);
    }
}
