package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreparedRequestTest {
    @Test
    void toStringDoesNotPrintHeaderValues() {
        PreparedRequest request = new PreparedRequest(
                URI.create("https://tts.example.test/v1/speech"),
                Map.of("Authorization", "Bearer sk-secret"),
                new byte[] {1, 2, 3},
                "audio/wav");

        String text = request.toString();

        assertFalse(text.contains("sk-secret"), text);
        assertFalse(text.contains("Bearer"), text);
        assertTrue(text.contains("tts.example.test"), text);
    }

    @Test
    void aKeyInTheBodyIsNotPrintedEither() {
        PreparedRequest request = new PreparedRequest(
                URI.create("https://tts.example.test/v1/speech"),
                Map.of("xi-api-key", "sk-secret"),
                "{\"text\":\"hi\"}".getBytes(),
                "application/json");

        assertFalse(request.toString().contains("sk-secret"), request.toString());
    }
}
