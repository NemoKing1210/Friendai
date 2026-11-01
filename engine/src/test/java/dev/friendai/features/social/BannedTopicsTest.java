package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BannedTopicsTest {
    @Test
    void aPrivateSubjectIsRefusedInItsOwnLanguage() {
        assertTrue(BannedTopics.banned("how is your illness", "en"));
        assertTrue(BannedTopics.banned("как твоя болезнь", "ru"));
        assertTrue(BannedTopics.banned("你的癌症", "zh"));
    }

    @Test
    void anOrdinarySubjectIsAllowed() {
        assertFalse(BannedTopics.banned("the weather today", "en"));
        assertFalse(BannedTopics.banned("погода сегодня", "ru"));
    }

    @Test
    void aLearnedLanguageNameResolvesToItsCode() {
        assertTrue(BannedTopics.banned("как твоя болезнь", "Russian"));
        assertFalse(BannedTopics.banned("", "ru"));
        assertFalse(BannedTopics.banned(null, null));
    }

    @Test
    void aPrivateSubjectIsRefusedInEverySupportedLanguage() {
        assertTrue(BannedTopics.banned("dein Krebsbefund", "de"));
        assertTrue(BannedTopics.banned("ta maladie", "fr"));
        assertTrue(BannedTopics.banned("tu enfermedad", "es"));
        assertTrue(BannedTopics.banned("sua doença", "pt"));
        assertTrue(BannedTopics.banned("твоя хвороба", "uk"));
    }

    @Test
    void eachLanguageHasItsOwnListAndAnUnknownOneReadsAsEnglish() {
        assertTrue(BannedTopics.banned("my password is 123", "en"));
        assertFalse(BannedTopics.banned("my password is 123", "ru"), "one list is not applied to every language");
        assertTrue(BannedTopics.banned("my password is 123", "klingon"), "an unknown language falls back to English");
    }
}
