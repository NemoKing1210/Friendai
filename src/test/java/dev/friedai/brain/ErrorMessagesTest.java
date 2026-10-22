package dev.friedai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friedai.config.ConfigException;
import dev.friedai.llm.model.LlmErrorKind;
import dev.friedai.llm.model.LlmException;
import dev.friedai.text.UserFacingException;
import dev.friedai.text.UserMessage;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ErrorMessagesTest {
    @Test
    void authMapsToKey() {
        assertEquals(UserMessage.of("friedai.error.llm.auth"),
                ErrorMessages.forUser(new LlmException(LlmErrorKind.AUTH, "x")));
    }

    @Test
    void badRequestCarriesProviderMessage() {
        assertEquals(UserMessage.of("friedai.error.llm.bad_request", "m"),
                ErrorMessages.forUser(new LlmException(LlmErrorKind.BAD_REQUEST, "m")));
    }

    @Test
    void configExceptionIsNestedAndUnwrapped() {
        UserMessage inner = UserMessage.of("friedai.error.config.profile_missing", "x");
        assertEquals(UserMessage.of("friedai.error.config", inner),
                ErrorMessages.forUser(new CompletionException(new ConfigException(inner))));
    }

    @Test
    void userFacingExceptionPassesItsMessage() {
        UserMessage y = UserMessage.of("friedai.error.unknown");
        assertEquals(y, ErrorMessages.forUser(new UserFacingException(y)));
    }

    @Test
    void anythingElseIsUnknown() {
        assertEquals(UserMessage.of("friedai.error.unknown"), ErrorMessages.forUser(new RuntimeException("boom")));
    }
}
