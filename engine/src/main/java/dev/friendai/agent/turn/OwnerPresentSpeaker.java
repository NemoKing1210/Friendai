package dev.friendai.agent.turn;

import dev.friendai.foundation.text.UserMessage;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Speaks only while the owner is in the game. A companion whose owner is away (free time keeps it working) has
 * nobody to talk to, so its replies are not broadcast to other players; free time keeps its own summary instead.
 */
public final class OwnerPresentSpeaker implements Speaker {
    private final Speaker inner;
    private final BooleanSupplier ownerOnline;

    public OwnerPresentSpeaker(Speaker inner, BooleanSupplier ownerOnline) {
        this.inner = inner;
        this.ownerOnline = ownerOnline;
    }

    @Override
    public void say(String text) {
        if (ownerOnline.getAsBoolean()) {
            inner.say(text);
        }
    }

    @Override
    public void say(String text, UUID addressee) {
        if (ownerOnline.getAsBoolean()) {
            inner.say(text, addressee);
        }
    }

    @Override
    public void thinking(boolean on) {
        inner.thinking(on);
    }

    @Override
    public void error(UserMessage message) {
        inner.error(message);
    }
}
