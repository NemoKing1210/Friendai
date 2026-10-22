package dev.friedai.testutil;

import dev.friedai.brain.Speaker;
import dev.friedai.text.UserMessage;
import java.util.ArrayList;
import java.util.List;

public final class RecordingSpeaker implements Speaker {
    public final List<String> said = new ArrayList<>();
    public final List<UserMessage> errors = new ArrayList<>();
    public final List<Boolean> thinkingLog = new ArrayList<>();

    @Override
    public void say(String text) {
        said.add(text);
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
