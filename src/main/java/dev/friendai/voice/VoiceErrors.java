package dev.friendai.voice;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Maps transport failures to the messages a player sees. Bodies and headers never reach the message. */
public final class VoiceErrors {
    private VoiceErrors() {}

    /**
     * The message for a failed speech future: a {@link UserFacingException} anywhere in the causal
     * chain keeps its own message (auth, quota, network), anything else is the generic failure.
     */
    public static UserMessage userMessage(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof UserFacingException userFacing) {
            return userFacing.userMessage();
        }
        return UserMessage.of("friendai.voice.error.failed");
    }

    public static UserFacingException forStatus(int status) {
        String key = switch (status) {
            case 401, 403 -> "friendai.voice.error.auth";
            case 429 -> "friendai.voice.error.quota";
            default -> "friendai.voice.error.failed";
        };
        return new UserFacingException(UserMessage.of(key));
    }

    public static UserFacingException network() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.network"));
    }
}
