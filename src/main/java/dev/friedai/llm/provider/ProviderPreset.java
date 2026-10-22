package dev.friedai.llm.provider;

import java.util.List;

/**
 * Data describing a provider.
 *
 * @param defaultBaseUrl null when the profile must supply one
 * @param modelsProtocol protocol id used to list models
 */
public record ProviderPreset(
        String id,
        String displayName,
        String defaultBaseUrl,
        AuthStyle authStyle,
        List<RoutingRule> routing,
        String modelsProtocol) {}
