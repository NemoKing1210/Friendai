package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ServerConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonaPresetsTest {
    @Test
    void hasSixPresetsInOrder() {
        assertEquals(
                List.of("friendly", "cheerful", "calm", "witty", "adventurer", "gruff"),
                PersonaPresets.all().stream().map(PersonaPresets.Preset::id).toList());
    }

    @Test
    void idsAreUnique() {
        var ids = PersonaPresets.all().stream().map(PersonaPresets.Preset::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size());
    }

    @Test
    void everyTextParsesAndIsUnchangedByParse() {
        for (PersonaPresets.Preset preset : PersonaPresets.all()) {
            assertEquals(Optional.of(preset.text()), PersonaText.parse(preset.text()), preset.id());
        }
    }

    @Test
    void labelKeysUseThePresetNamespace() {
        for (PersonaPresets.Preset preset : PersonaPresets.all()) {
            assertEquals("friendai.menu.persona.preset." + preset.id(), preset.labelKey());
        }
    }

    @Test
    void friendlyIsTheServerDefault() {
        PersonaPresets.Preset first = PersonaPresets.all().get(0);
        assertEquals("friendly", first.id());
        assertEquals(new ServerConfig.CompanionSection().description, first.text());
    }

    @Test
    void matchingFindsByExactText() {
        PersonaPresets.Preset calm = PersonaPresets.all().get(2);
        assertEquals(Optional.of(calm), PersonaPresets.matching(calm.text()));
        assertTrue(PersonaPresets.matching("nope").isEmpty());
    }
}
