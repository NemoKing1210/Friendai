package dev.friendai.agent.llm.provider;

import java.util.Map;
import java.util.Optional;

/** Where a player creates the key for a preset, if the provider has a public key page. */
public final class ProviderDocs {
    private static final Map<String, String> URLS = Map.ofEntries(
            Map.entry("openai", "https://platform.openai.com/api-keys"),
            Map.entry("anthropic", "https://console.anthropic.com/settings/keys"),
            Map.entry("gemini", "https://aistudio.google.com/app/apikey"),
            Map.entry("openrouter", "https://openrouter.ai/keys"),
            Map.entry("deepseek", "https://platform.deepseek.com/api_keys"),
            Map.entry("groq", "https://console.groq.com/keys"),
            Map.entry("mistral", "https://console.mistral.ai/api-keys"),
            Map.entry("xai", "https://console.x.ai/"),
            Map.entry("together", "https://api.together.ai/settings/api-keys"),
            Map.entry("fireworks", "https://fireworks.ai/account/api-keys"),
            Map.entry("cerebras", "https://cloud.cerebras.ai/"),
            Map.entry("github_models", "https://github.com/settings/tokens"),
            Map.entry("opencode_zen", "https://opencode.ai/docs/zen/"),
            Map.entry("opencode_go", "https://opencode.ai/docs/go/"),
            Map.entry("command_code", "https://commandcode.ai/"));

    private ProviderDocs() {}

    public static Optional<String> url(String providerId) {
        return Optional.ofNullable(URLS.get(providerId));
    }
}
