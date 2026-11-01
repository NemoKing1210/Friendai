package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class DiagnosticRedactionTest {
    @Test
    void masksSecretsAndStripsUrlCredentials() {
        String masked =
                DiagnosticRedaction.maskConfig(JsonParser.parseString("""
                {"activeProfile":"main","profiles":{"main":{
                  "provider":"openai","model":"gpt","apiKey":"sk-live-secret",
                  "baseUrl":"https://user:pass@api.example.com/v1?token=abc#frag"}}}
                """)).toString();
        assertFalse(masked.contains("sk-live-secret"), masked);
        assertFalse(masked.contains("user:pass"), masked);
        assertFalse(masked.contains("token=abc"), masked);
        assertTrue(masked.contains("\"apiKey\":\"set\""), masked);
        assertTrue(masked.contains("\"baseUrl\":\"https://api.example.com/v1\""), masked);
        assertTrue(masked.contains("\"provider\":\"openai\""), masked);
    }

    @Test
    void masksBlankAndNestedSecrets() {
        String masked =
                DiagnosticRedaction.maskConfig(JsonParser.parseString("""
                {"apiKey":"","token":"abc","nested":[{"password":"hunter2"}]}
                """)).toString();
        assertTrue(masked.contains("\"apiKey\":\"not set\""), masked);
        assertTrue(masked.contains("\"token\":\"set\""), masked);
        assertTrue(masked.contains("\"password\":\"set\""), masked);
        assertFalse(masked.contains("hunter2"), masked);
    }

    @Test
    void aNonUrlIsNotLeaked() {
        assertEquals(
                "{\"baseUrl\":\"set\"}",
                DiagnosticRedaction.maskConfig(JsonParser.parseString("""
                        {"baseUrl":"env:PRIVATE_ENDPOINT"}
                        """)).toString());
    }
}
