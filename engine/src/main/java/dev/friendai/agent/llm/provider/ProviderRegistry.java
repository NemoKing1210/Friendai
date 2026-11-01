package dev.friendai.agent.llm.provider;

import dev.friendai.agent.llm.JsonHttpTransport;
import dev.friendai.agent.llm.protocol.AnthropicMessagesProtocol;
import dev.friendai.agent.llm.protocol.GeminiProtocol;
import dev.friendai.agent.llm.protocol.LlmProtocol;
import dev.friendai.agent.llm.protocol.OllamaProtocol;
import dev.friendai.agent.llm.protocol.OpenAiChatProtocol;
import dev.friendai.agent.llm.protocol.OpenAiResponsesProtocol;
import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.foundation.text.UserMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ProviderRegistry {
    private static final String ZEN = "https://opencode.ai/inference";

    private static final String GO = ZEN + "/go";

    private final Map<String, LlmProtocol> protocols = new LinkedHashMap<>();
    private final Map<String, ProviderPreset> presets = new LinkedHashMap<>();

    private ProviderRegistry() {}

    public static ProviderRegistry withBuiltins(JsonHttpTransport transport) {
        ProviderRegistry r = new ProviderRegistry();
        for (LlmProtocol p : List.of(
                new OpenAiChatProtocol(transport),
                new OpenAiResponsesProtocol(transport),
                new AnthropicMessagesProtocol(transport),
                new GeminiProtocol(transport),
                new OllamaProtocol(transport))) {
            r.protocols.put(p.id(), p);
        }
        r.add(new ProviderPreset(
                "openai",
                "OpenAI",
                "https://api.openai.com/v1",
                AuthStyle.BEARER,
                List.of(rule("*", "openai_responses")),
                "openai_chat"));
        r.add(new ProviderPreset(
                "openai_compat",
                "OpenAI-compatible",
                null,
                AuthStyle.BEARER,
                List.of(rule("*", "openai_chat")),
                "openai_chat"));
        r.add(new ProviderPreset(
                "anthropic",
                "Anthropic",
                "https://api.anthropic.com/v1",
                AuthStyle.X_API_KEY,
                List.of(rule("*", "anthropic_messages")),
                "anthropic_messages"));
        r.add(new ProviderPreset(
                "gemini",
                "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta",
                AuthStyle.GOOG_API_KEY,
                List.of(rule("*", "gemini")),
                "gemini"));
        r.add(new ProviderPreset(
                "ollama", "Ollama", "http://localhost:11434", AuthStyle.NONE, List.of(rule("*", "ollama")), "ollama"));
        // OpenCode Zen exposes one path prefix per protocol family under /inference (docs checked 2026-10-22).
        r.add(new ProviderPreset(
                "opencode_zen",
                "OpenCode Zen",
                ZEN + "/v1",
                AuthStyle.BEARER,
                List.of(
                        new RoutingRule("claude-*", "anthropic_messages", ZEN + "/anthropic/v1"),
                        new RoutingRule("gpt-*", "openai_responses", ZEN + "/openai/v1"),
                        new RoutingRule("gemini-*", "gemini", ZEN + "/google/v1beta"),
                        new RoutingRule("*", "openai_chat", ZEN + "/openai/v1")),
                "openai_chat"));
        // OpenCode Go: same layout as Zen under /inference/go; the model list is at /go/v1/models (docs checked
        // 2026-10-23).
        r.add(new ProviderPreset(
                "opencode_go",
                "OpenCode Go",
                GO + "/v1",
                AuthStyle.BEARER,
                List.of(
                        new RoutingRule("claude-*", "anthropic_messages", GO + "/anthropic/v1"),
                        new RoutingRule("minimax-m3", "anthropic_messages", GO + "/anthropic/v1"),
                        new RoutingRule("qwen3.*", "anthropic_messages", GO + "/anthropic/v1"),
                        new RoutingRule("gpt-*", "openai_responses", GO + "/openai/v1"),
                        new RoutingRule("grok-*", "openai_responses", GO + "/openai/v1"),
                        new RoutingRule("muse-*", "openai_responses", GO + "/openai/v1"),
                        new RoutingRule("gemini-*", "gemini", GO + "/google/v1beta"),
                        new RoutingRule("*", "openai_chat", GO + "/openai/v1")),
                "openai_chat",
                // Go rejects requests without a session id and asks clients to use their own user agent.
                Map.of("User-Agent", "friendai/1.0"),
                "x-opencode-session"));
        r.add(new ProviderPreset(
                "command_code",
                "Command Code",
                "https://api.commandcode.ai/provider/v1",
                AuthStyle.BEARER,
                List.of(rule("claude-*", "anthropic_messages"), rule("*", "openai_chat")),
                "openai_chat"));
        r.add(openAiCompatible("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"));
        r.add(openAiCompatible("deepseek", "DeepSeek", "https://api.deepseek.com/v1"));
        r.add(openAiCompatible("groq", "Groq", "https://api.groq.com/openai/v1"));
        r.add(openAiCompatible("mistral", "Mistral", "https://api.mistral.ai/v1"));
        r.add(openAiCompatible("xai", "xAI", "https://api.x.ai/v1"));
        r.add(openAiCompatible("together", "Together AI", "https://api.together.xyz/v1"));
        r.add(openAiCompatible("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1"));
        r.add(openAiCompatible("cerebras", "Cerebras", "https://api.cerebras.ai/v1"));
        r.add(openAiCompatible("github_models", "GitHub Models", "https://models.inference.ai.azure.com"));
        return r;
    }

    private static ProviderPreset openAiCompatible(String id, String name, String baseUrl) {
        return new ProviderPreset(
                id, name, baseUrl, AuthStyle.BEARER, List.of(rule("*", "openai_chat")), "openai_chat");
    }

    /** One id per registry, i.e. per server run; sent to providers that ask for a stable session. */
    private final String sessionId = UUID.randomUUID().toString();

    private static RoutingRule rule(String glob, String protocol) {
        return new RoutingRule(glob, protocol, null);
    }

    private void add(ProviderPreset p) {
        presets.put(p.id(), p);
    }

    public List<ProviderPreset> presets() {
        return List.copyOf(presets.values());
    }

    public Optional<ProviderPreset> preset(String id) {
        return Optional.ofNullable(presets.get(id));
    }

    public Route resolve(LlmProfile profile) {
        ProviderPreset preset = requirePreset(profile);
        List<RoutingRule> rules = profile.routingOverride() != null ? profile.routingOverride() : preset.routing();
        RoutingRule rule = rules.stream()
                .filter(r -> r.matches(profile.model()))
                .findFirst()
                .orElseThrow(
                        () -> new ConfigException(UserMessage.of("friendai.error.config.no_route", profile.model())));
        LlmProtocol protocol = protocols.get(rule.protocol());
        if (protocol == null) {
            throw new ConfigException(UserMessage.of("friendai.error.config.no_route", profile.model()));
        }
        String base = firstNonBlank(profile.baseUrl(), rule.baseUrlOverride(), preset.defaultBaseUrl());
        return route(protocol, preset, profile, base);
    }

    public Route resolveModels(LlmProfile profile) {
        ProviderPreset preset = requirePreset(profile);
        LlmProtocol protocol = protocols.get(preset.modelsProtocol());
        String base = firstNonBlank(profile.baseUrl(), preset.defaultBaseUrl());
        return route(protocol, preset, profile, base);
    }

    private ProviderPreset requirePreset(LlmProfile profile) {
        return preset(profile.provider())
                .orElseThrow(() -> new ConfigException(
                        UserMessage.of("friendai.error.config.unknown_provider", profile.provider())));
    }

    private Route route(LlmProtocol protocol, ProviderPreset preset, LlmProfile profile, String base) {
        if (base == null) {
            throw new ConfigException(UserMessage.of("friendai.error.config.needs_base_url", preset.id()));
        }
        String trimmed = base.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        Map<String, String> extra = new LinkedHashMap<>(preset.headers());
        if (preset.sessionHeader() != null) {
            extra.put(preset.sessionHeader(), sessionId);
        }
        return new Route(protocol, new Endpoint(trimmed, profile.apiKey(), preset.authStyle(), extra));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
