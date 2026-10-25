package dev.friendai.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoiceLanguagesTest {
    @Test
    void yandexNeedsATagAndOthersDoNot() {
        assertEquals("ru-RU", VoiceLanguages.sttLanguage("yandex", "auto", "ru_ru"));
        assertEquals("en-US", VoiceLanguages.sttLanguage("yandex", "auto", "de_de"));
        assertEquals("", VoiceLanguages.sttLanguage("openai", "auto", "ru_ru"));
        assertEquals("ru-RU", VoiceLanguages.ttsLanguage("yandex", "auto", "ru_ru"));
        assertEquals("", VoiceLanguages.ttsLanguage("openai", "ru_ru", "ru_ru"));
        assertTrue(VoiceLanguages.yandexTag("ja-JP").isEmpty());
    }

    @Test
    void languageMapping() {
        assertEquals("ru", VoiceLanguages.iso639("ru_ru"));
        assertEquals("en", VoiceLanguages.iso639(null));
        assertEquals("ru-RU", VoiceLanguages.yandexTag("ru-RU"));
        assertEquals("ru-RU", VoiceLanguages.yandexTag("ru"));
        assertEquals("kk-KK", VoiceLanguages.yandexTag("kk"));
        assertEquals("", VoiceLanguages.yandexTag("ja"));
        assertEquals("ru", VoiceLanguages.sttLanguage("openai", "ru", "en_us"));
        assertEquals("", VoiceLanguages.ttsLanguage("elevenlabs", "ru", "ru_ru"));
    }
}
