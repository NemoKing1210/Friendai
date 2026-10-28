package dev.friendai.core;

import dev.friendai.foundation.text.UserMessage;
import net.minecraft.network.chat.Component;

public final class Components {
    private Components() {}

    public static Component of(UserMessage m) {
        Object[] args = m.args().stream()
                .map(a -> a instanceof UserMessage nested ? of(nested) : a)
                .toArray();
        return Component.translatable(m.key(), args);
    }
}
