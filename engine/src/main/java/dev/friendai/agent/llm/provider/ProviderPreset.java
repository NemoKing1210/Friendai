package dev.friendai.agent.llm.provider;

import java.util.List;
import java.util.Map;

/**
 * Data describing a provider.
 *
 * @param defaultBaseUrl null when the profile must supply one
 * @param modelsProtocol protocol id used to list models
 * @param headers fixed headers sent with every request
 * @param sessionHeader name of a header that carries a stable per-run session id, or null
 */
public record ProviderPreset(
        String id,
        String displayName,
        String defaultBaseUrl,
        AuthStyle authStyle,
        List<RoutingRule> routing,
        String modelsProtocol,
        Map<String, String> headers,
        String sessionHeader) {

    public ProviderPreset(
            String id,
            String displayName,
            String defaultBaseUrl,
            AuthStyle authStyle,
            List<RoutingRule> routing,
            String modelsProtocol) {
        this(id, displayName, defaultBaseUrl, authStyle, routing, modelsProtocol, Map.of(), null);
    }
}
