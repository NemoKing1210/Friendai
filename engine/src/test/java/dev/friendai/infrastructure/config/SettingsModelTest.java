package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsModelTest {
    private static final String API_KEY = "friendai.config.api_key";
    private static final String HEARING = "friendai.config.chat.hearing_radius";
    private static final String PREFIX = "friendai.config.chat.prefix";

    @Test
    void labelWordsMatch() {
        assertTrue(SettingsModel.matches("hearing", HEARING));
        assertTrue(SettingsModel.matches("radius", HEARING));
        assertTrue(SettingsModel.matches("prefix", PREFIX));
    }

    @Test
    void synonymsMatch() {
        assertTrue(SettingsModel.matches("token", API_KEY));
        assertTrue(SettingsModel.matches("secret", API_KEY));
        assertTrue(SettingsModel.matches("microphone", "friendai.config.voice.enabled"));
        assertTrue(SettingsModel.matches("eavesdrop", "friendai.config.chat.listen_all"));
    }

    @Test
    void matchingIgnoresCase() {
        assertTrue(SettingsModel.matches("API KEY", API_KEY));
        assertTrue(SettingsModel.matches("Hearing RADIUS", HEARING));
    }

    @Test
    void everyWordMustMatch() {
        assertTrue(SettingsModel.matches("chat prefix", PREFIX));
        assertFalse(SettingsModel.matches("chat prefix", "friendai.config.chat.listen_all"));
        assertFalse(SettingsModel.matches("hearing volume", HEARING));
    }

    @Test
    void blankQueryMatchesEverything() {
        for (SettingsModel.Entry entry : SettingsModel.catalogue()) {
            assertTrue(SettingsModel.matches("", entry.key()), entry.key());
            assertTrue(SettingsModel.matches("   ", entry.key()), entry.key());
            assertTrue(SettingsModel.matches(null, entry.key()), entry.key());
        }
        assertEquals(SettingsModel.catalogue().size(), SettingsModel.search("").size());
    }

    @Test
    void unknownWordMatchesNothing() {
        assertFalse(SettingsModel.matches("zzzz", PREFIX));
        assertTrue(SettingsModel.search("zzzz").isEmpty());
        assertFalse(SettingsModel.matches("zzzz", "friendai.config.some_future_option"));
    }

    @Test
    void unknownKeysMatchOnTheirOwnWords() {
        assertTrue(SettingsModel.matches("load models", "friendai.config.load_models"));
        assertFalse(SettingsModel.matches("voice", "friendai.config.load_models"));
    }

    @Test
    void searchReturnsCatalogueKeysOnly() {
        Set<String> hearing = SettingsModel.search("hearing");
        assertTrue(hearing.contains(HEARING));
        for (String key : hearing) {
            assertTrue(SettingsModel.entry(key).isPresent(), key);
        }
        assertTrue(SettingsModel.search("token").contains(API_KEY));
    }

    @Test
    void basicModeHidesAdvancedOptions() {
        assertFalse(SettingsModel.show(API_KEY, false));
        assertFalse(SettingsModel.show(HEARING, false));
        assertTrue(SettingsModel.show(API_KEY, true));
        assertTrue(SettingsModel.show(HEARING, true));
        assertTrue(SettingsModel.show(PREFIX, false));
        assertTrue(SettingsModel.show("friendai.config.companion.name", false));
    }

    @Test
    void unknownKeyIsNeverHidden() {
        assertTrue(SettingsModel.show("friendai.config.some_future_option", false));
        assertTrue(SettingsModel.show("friendai.config.some_future_option", true));
        assertTrue(SettingsModel.show(null, false));
    }

    @Test
    void advancedMarksKeysUrlsBudgetsRadiiAndLists() {
        List<String> advanced = List.of(
                "friendai.config.api_key",
                "friendai.config.base_url",
                "friendai.config.skills.budget",
                "friendai.config.agent.prompt_budget",
                "friendai.config.chat.hearing_radius",
                "friendai.config.tasks.mine_search_radius",
                "friendai.config.mining.valuable_blocks",
                "friendai.config.memory.max_world_facts");
        for (String key : advanced) {
            assertFalse(SettingsModel.show(key, false), key);
        }
        for (String key : List.of(
                "friendai.config.provider",
                "friendai.config.model",
                "friendai.config.companion.name",
                "friendai.config.voice.tts.volume",
                "friendai.config.autonomy.enabled")) {
            assertTrue(SettingsModel.show(key, false), key);
        }
    }

    @Test
    void catalogueHasUniqueKeysAndKnownCategories() {
        Set<String> keys = new HashSet<>();
        for (SettingsModel.Entry entry : SettingsModel.catalogue()) {
            assertTrue(keys.add(entry.key()), "duplicate key " + entry.key());
            assertEquals(entry.key(), entry.labelKey());
            assertTrue(
                    List.of(
                                    "provider",
                                    "companion",
                                    "chat",
                                    "planning",
                                    "tasks",
                                    "world",
                                    "trading",
                                    "resources",
                                    "autonomy",
                                    "memory",
                                    "mining",
                                    "display",
                                    "voice")
                            .contains(entry.category()),
                    entry.category());
        }
    }

    @Test
    void categoriesKeepScreenOrder() {
        assertEquals(
                List.of(
                        "provider",
                        "companion",
                        "chat",
                        "planning",
                        "tasks",
                        "world",
                        "trading",
                        "resources",
                        "autonomy",
                        "memory",
                        "mining",
                        "display",
                        "voice"),
                SettingsModel.categories());
    }

    @Test
    void entrySearchTermsAreLowerCase() {
        SettingsModel.Entry apiKey = SettingsModel.entry(API_KEY).orElseThrow();
        assertTrue(apiKey.advanced());
        assertEquals(apiKey.searchTerms().stream().map(String::toLowerCase).toList(), apiKey.searchTerms());
    }
}
