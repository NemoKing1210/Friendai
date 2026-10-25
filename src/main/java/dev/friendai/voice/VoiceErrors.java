package dev.friendai.voice;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;

/** Maps transport failures to the messages a player sees. Bodies and headers never reach the message. */
public final class VoiceErrors {
    private VoiceErrors() {}

    public static UserFacingException forStatus(int status) {
        String key = switch (status) {
            case 401, 403 -> "friendai.voice.error.auth";
            case 429 -> "friendai.voice.error.quota";
            default -> "friendai.voice.error.failed";
        };
        return new UserFacingException(UserMessage.of(key));
    }

    public static UserFacingException network(Throwable cause) {
        return new UserFacingException(UserMessage.of("friendai.voice.error.network"));
    }
}
