package dev.friedai.config;

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
    void mask() {
        assertEquals("sk-…456", SecretResolver.mask("sk-abcdef123456"));
        assertEquals("•••", SecretResolver.mask("short"));
        assertEquals("", SecretResolver.mask(""));
        assertEquals("env:ANTHROPIC_API_KEY", SecretResolver.mask("env:ANTHROPIC_API_KEY"));
    }
}
