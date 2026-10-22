package dev.friedai.config;

import dev.friedai.text.UserFacingException;
import dev.friedai.text.UserMessage;

/** A problem with the player's settings. */
public final class ConfigException extends UserFacingException {
    public ConfigException(UserMessage message) {
        super(message);
    }
}
