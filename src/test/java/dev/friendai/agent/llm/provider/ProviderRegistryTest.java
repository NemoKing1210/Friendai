package dev.friendai.agent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.llm.http.HttpTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {
    private final ProviderRegistry registry = ProviderRegistry.withBuiltins(
            new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));

    private static LlmProfile profile(String provider, String model, String baseUrl, List<RoutingRule> override) {
        return new LlmProfile(provider, model, "key", baseUrl, override, 0.7, 1024);
    }

    @Test
    void zenRoutesClaudeToAnthropic() {
        Route r = registry.resolve(profile("opencode_zen", "claude-sonnet-5-5", null, null));
        assertEquals("anthropic_messages", r.protocol().id());
        assertEquals("https://opencode.ai/inference/anthropic/v1", r.endpoint().baseUrl());
    }

    @Test
    void zenRoutesGptToResponses() {
        Route r = registry.resolve(profile("opencode_zen", "gpt-5", null, null));
        assertEquals("openai_responses", r.protocol().id());
    }

    @Test
    void zenRoutesGeminiToGemini() {
        Route r = registry.resolve(profile("opencode_zen", "gemini-3.1-pro", null, null));
        assertEquals("gemini", r.protocol().id());
        assertEquals("https://opencode.ai/inference/google/v1beta", r.endpoint().baseUrl());
    }

    @Test
    void zenFallsBackToChat() {
        Route r = registry.resolve(profile("opencode_zen", "qwen3-coder", null, null));
        assertEquals("openai_chat", r.protocol().id());
        assertEquals("https://opencode.ai/inference/openai/v1", r.endpoint().baseUrl());
    }

    @Test
    void goRoutesEachModelFamily() {
        assertGo("claude-haiku-5-5", "anthropic_messages", "https://opencode.ai/inference/go/anthropic/v1");
        assertGo("minimax-m3", "anthropic_messages", "https://opencode.ai/inference/go/anthropic/v1");
        assertGo("qwen3.8-max", "anthropic_messages", "https://opencode.ai/inference/go/anthropic/v1");
        assertGo("gpt-6-luna", "openai_responses", "https://opencode.ai/inference/go/openai/v1");
        assertGo("grok-4.7", "openai_responses", "https://opencode.ai/inference/go/openai/v1");
        assertGo("muse-spark-1.3-contributor", "openai_responses", "https://opencode.ai/inference/go/openai/v1");
        assertGo("gemini-4-argon-contributor", "gemini", "https://opencode.ai/inference/go/google/v1beta");
        assertGo("kimi-k3", "openai_chat", "https://opencode.ai/inference/go/openai/v1");
        assertGo("minimax-m3.1-flash", "openai_chat", "https://opencode.ai/inference/go/openai/v1");
    }

    @Test
    void goListsModelsFromGoEndpoint() {
        Route r = registry.resolveModels(profile("opencode_go", "kimi-k3", null, null));
        assertEquals("https://opencode.ai/inference/go/v1", r.endpoint().baseUrl());
    }

    @Test
    void goSendsStableSessionHeaderAndOwnUserAgent() {
        Map<String, String> first = registry.resolve(profile("opencode_go", "kimi-k3", null, null))
                .endpoint()
                .extraHeaders();
        Map<String, String> second = registry.resolve(profile("opencode_go", "claude-haiku-5-5", null, null))
                .endpoint()
                .extraHeaders();
        Map<String, String> models = registry.resolveModels(profile("opencode_go", "kimi-k3", null, null))
                .endpoint()
                .extraHeaders();
        assertTrue(!first.get("x-opencode-session").isBlank());
        assertEquals(first.get("x-opencode-session"), second.get("x-opencode-session"));
        assertEquals(first.get("x-opencode-session"), models.get("x-opencode-session"));
        assertEquals("friendai/1.0", first.get("User-Agent"));
    }

    @Test
    void otherProvidersSendNoExtraHeaders() {
        assertTrue(registry.resolve(profile("opencode_zen", "kimi-k3", null, null))
                .endpoint()
                .extraHeaders()
                .isEmpty());
    }

    private void assertGo(String model, String protocol, String baseUrl) {
        Route r = registry.resolve(profile("opencode_go", model, null, null));
        assertEquals(protocol, r.protocol().id(), model);
        assertEquals(baseUrl, r.endpoint().baseUrl(), model);
    }

    @Test
    void commandCodeRoutesClaude() {
        Route r = registry.resolve(profile("command_code", "claude-opus-5-5", null, null));
        assertEquals("anthropic_messages", r.protocol().id());
        assertEquals("https://api.commandcode.ai/provider/v1", r.endpoint().baseUrl());
    }

    @Test
    void profileBaseUrlOverridesPreset() {
        Route r = registry.resolve(profile("ollama", "llama3", "http://192.168.1.5:11434/", null));
        assertEquals("http://192.168.1.5:11434", r.endpoint().baseUrl());
    }

    @Test
    void routingOverrideWins() {
        Route r = registry.resolve(
                profile("opencode_zen", "claude-x", null, List.of(new RoutingRule("*", "openai_chat", null))));
        assertEquals("openai_chat", r.protocol().id());
    }

    @Test
    void globIsCaseInsensitiveAndAnchored() {
        assertTrue(new RoutingRule("claude-*", "x", null).matches("Claude-Sonnet"));
        assertTrue(!new RoutingRule("gpt-*", "x", null).matches("my-gpt-4"));
    }

    @Test
    void compatWithoutBaseUrlFails() {
        ConfigException e =
                assertThrows(ConfigException.class, () -> registry.resolve(profile("openai_compat", "m", null, null)));
        assertEquals(UserMessage.of("friendai.error.config.needs_base_url", "openai_compat"), e.userMessage());
    }

    @Test
    void unknownProviderFails() {
        ConfigException e =
                assertThrows(ConfigException.class, () -> registry.resolve(profile("nope", "m", null, null)));
        assertEquals("friendai.error.config.unknown_provider", e.userMessage().key());
    }

    @Test
    void resolveModelsUsesModelsProtocol() {
        Route r = registry.resolveModels(profile("opencode_zen", "claude-x", null, null));
        assertEquals("openai_chat", r.protocol().id());
        assertEquals("https://opencode.ai/inference/v1", r.endpoint().baseUrl());
    }
}
