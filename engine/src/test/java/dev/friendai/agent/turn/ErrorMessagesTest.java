package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ErrorMessagesTest {
    @Test
    void authMapsToKey() {
        assertEquals(
                UserMessage.of("friendai.error.llm.auth"),
                ErrorMessages.forUser(new LlmException(LlmErrorKind.AUTH, "x")));
    }

    @Test
    void badRequestCarriesProviderMessage() {
        assertEquals(
                UserMessage.of("friendai.error.llm.bad_request", "m"),
                ErrorMessages.forUser(new LlmException(LlmErrorKind.BAD_REQUEST, "m")));
    }

    @Test
    void configExceptionIsNestedAndUnwrapped() {
        UserMessage inner = UserMessage.of("friendai.error.config.profile_missing", "x");
        assertEquals(
                UserMessage.of("friendai.error.config", inner),
                ErrorMessages.forUser(new CompletionException(new ConfigException(inner))));
    }

    @Test
    void userFacingExceptionPassesItsMessage() {
        UserMessage y = UserMessage.of("friendai.error.unknown");
        assertEquals(y, ErrorMessages.forUser(new UserFacingException(y)));
    }

    @Test
    void anythingElseIsUnknown() {
        assertEquals(UserMessage.of("friendai.error.unknown"), ErrorMessages.forUser(new RuntimeException("boom")));
    }
}
