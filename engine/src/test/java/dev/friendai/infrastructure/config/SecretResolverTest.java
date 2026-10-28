package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretResolverTest {
    @Test
    void envPrefix() {
        assertEquals("v", SecretResolver.resolve("env:K", Map.of("K", "v")::get));
        assertEquals("", SecretResolver.resolve("env:MISSING", Map.of("K", "v")::get));
        assertEquals("plain-key", SecretResolver.resolve("plain-key", Map.of("K", "v")::get));
    }

    @Test
    void displayHidesKeyByDefault() {
        assertEquals("••••••••", SecretResolver.display("sk-abcdef123456", false));
        assertEquals("••••••••", SecretResolver.display("short", false));
        assertEquals("", SecretResolver.display("", false));
        assertEquals("", SecretResolver.display(null, false));
        assertEquals("env:ANTHROPIC_API_KEY", SecretResolver.display("env:ANTHROPIC_API_KEY", false));
    }

    @Test
    void displayRevealsKeyOnRequest() {
        assertEquals("sk-abcdef123456", SecretResolver.display("sk-abcdef123456", true));
        assertEquals("", SecretResolver.display(null, true));
    }
}
