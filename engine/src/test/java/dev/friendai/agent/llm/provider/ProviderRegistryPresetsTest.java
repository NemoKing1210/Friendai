package dev.friendai.agent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderRegistryPresetsTest {
    private static final Set<String> NAMED = Set.of(
            "openrouter", "deepseek", "groq", "mistral", "xai", "together", "fireworks", "cerebras", "github_models");

    @Test
    void everyPresetHasAUniqueIdBaseUrlAndModelProtocol() {
        ProviderRegistry registry = ProviderRegistry.withBuiltins(null);
        Set<String> ids = new HashSet<>();
        for (ProviderPreset preset : registry.presets()) {
            assertTrue(ids.add(preset.id()), "duplicate provider id " + preset.id());
            assertNotNull(preset.displayName(), preset.id());
            assertFalse(preset.displayName().isBlank(), preset.id());
            if (!"openai_compat".equals(preset.id())) {
                assertNotNull(preset.defaultBaseUrl(), preset.id());
                assertFalse(preset.defaultBaseUrl().isBlank(), preset.id());
            }
            assertNotNull(preset.modelsProtocol(), preset.id());
            assertFalse(preset.routing().isEmpty(), preset.id());
            assertTrue(preset.routing().stream().anyMatch(rule -> rule.matches("model")), preset.id());
        }
        assertTrue(ids.containsAll(NAMED), "missing named presets: " + ids);
    }

    @Test
    void openRouterIsNoLongerOnlyAnOpenAiCompatRecipe() {
        ProviderRegistry registry = ProviderRegistry.withBuiltins(null);
        ProviderPreset openRouter = registry.preset("openrouter").orElseThrow();
        assertEquals("OpenRouter", openRouter.displayName());
        assertEquals("https://openrouter.ai/api/v1", openRouter.defaultBaseUrl());
        assertEquals(AuthStyle.BEARER, openRouter.authStyle());
    }
}
