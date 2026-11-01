package dev.friendai.agent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderDocsTest {
    @Test
    void everyKeyedPresetHasAKeyPage() {
        List<String> missing = new ArrayList<>();
        for (ProviderPreset preset : ProviderRegistry.withBuiltins(null).presets()) {
            if (preset.authStyle() == AuthStyle.NONE || "openai_compat".equals(preset.id())) {
                continue;
            }
            if (ProviderDocs.url(preset.id()).isEmpty()) {
                missing.add(preset.id());
            }
        }
        assertTrue(missing.isEmpty(), "Missing key pages: " + missing);
    }
}
