package dev.friendai.foundation.diagnostics;

import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;

/** A problem with the player's settings. */
public final class ConfigException extends UserFacingException {
    public ConfigException(UserMessage message) {
        super(message);
    }
}
