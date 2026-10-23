package dev.friendai.config;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;

/** A problem with the player's settings. */
public final class ConfigException extends UserFacingException {
    public ConfigException(UserMessage message) {
        super(message);
    }
}
