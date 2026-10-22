package dev.friedai.brain;

import dev.friedai.config.ConfigException;
import dev.friedai.llm.model.LlmErrorKind;
import dev.friedai.llm.model.LlmException;
import dev.friedai.text.UserFacingException;
import dev.friedai.text.UserMessage;
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
                return UserMessage.of("friedai.error.llm.bad_request", e.getMessage());
            }
            // Built from parts so LangFilesTest does not mistake a key prefix for a full key.
            return UserMessage.of(String.join(".", "friedai", "error", "llm", e.kind().name().toLowerCase()));
        }
        if (t instanceof ConfigException e) {
            return UserMessage.of("friedai.error.config", e.userMessage());
        }
        if (t instanceof UserFacingException e) {
            return e.userMessage();
        }
        return UserMessage.of("friedai.error.unknown");
    }
}
