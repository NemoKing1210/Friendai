package dev.friendai.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class VoiceErrorsTest {
    @Test
    void userFacingExceptionKeepsItsMessage() {
        UserMessage auth = UserMessage.of("friendai.voice.error.auth");
        assertEquals(auth, VoiceErrors.userMessage(new UserFacingException(auth)));
    }

    @Test
    void wrappedUserFacingExceptionIsUnwrapped() {
        UserMessage quota = UserMessage.of("friendai.voice.error.quota");
        assertEquals(quota, VoiceErrors.userMessage(new CompletionException(new UserFacingException(quota))));
        assertEquals(quota, VoiceErrors.userMessage(new ExecutionException(new UserFacingException(quota))));
    }

    @Test
    void anythingElseBecomesTheGenericFailure() {
        assertEquals(
                UserMessage.of("friendai.voice.error.failed"),
                VoiceErrors.userMessage(new IllegalStateException("boom")));
        assertEquals(UserMessage.of("friendai.voice.error.failed"), VoiceErrors.userMessage(null));
    }
}
