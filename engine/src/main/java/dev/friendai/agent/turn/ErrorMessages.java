package dev.friendai.agent.turn;

import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Turns failures into messages the player can read. */
public final class ErrorMessages {
    private ErrorMessages() {}

    public static UserMessage forUser(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof LlmException e) {
            if (e.kind() == LlmErrorKind.BAD_REQUEST) {
                return UserMessage.of("friendai.error.llm.bad_request", e.getMessage());
            }
            // Built from parts so LangFilesTest does not mistake a key prefix for a full key.
            return UserMessage.of(
                    String.join(".", "friendai", "error", "llm", e.kind().name().toLowerCase()));
        }
        if (t instanceof ConfigException e) {
            return UserMessage.of("friendai.error.config", e.userMessage());
        }
        if (t instanceof UserFacingException e) {
            return e.userMessage();
        }
        return UserMessage.of("friendai.error.unknown");
    }
}
