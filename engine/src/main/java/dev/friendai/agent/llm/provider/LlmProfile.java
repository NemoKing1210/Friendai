package dev.friendai.agent.llm.provider;

import java.util.List;

/**
 * A resolved profile ready for use.
 *
 * @param apiKey already resolved (no "env:" prefix)
 * @param baseUrl nullable, overrides the preset
 * @param routingOverride nullable, replaces the preset routing
 */
public record LlmProfile(
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        List<RoutingRule> routingOverride,
        Double temperature,
        int maxTokens) {
    @Override
    public String toString() {
        return "LlmProfile[provider=" + provider + ", model=" + model + ", apiKey=***]";
    }
}
