package dev.friendai.memory;

import dev.friendai.foundation.text.UserMessage;

/** The result of one memory menu action: a success or an error message for the status line. */
public record MenuStatus(boolean ok, UserMessage message) {
    public static MenuStatus ok(String key, Object... args) {
        return new MenuStatus(true, UserMessage.of(key, args));
    }

    public static MenuStatus error(String key, Object... args) {
        return new MenuStatus(false, UserMessage.of(key, args));
    }
}
