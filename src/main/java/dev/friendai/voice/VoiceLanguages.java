package dev.friendai.voice;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps the game's language to what a speech provider expects. */
public final class VoiceLanguages {
    private static final Set<String> YANDEX_TAGS =
            Set.of("ru-RU", "en-US", "tr-TR", "de-DE", "es-ES", "fr-FR", "it-IT", "uz-UZ", "kk-KK");

    private static final Map<String, String> YANDEX_ISO = Map.of(
            "ru", "ru-RU", "en", "en-US", "tr", "tr-TR", "de", "de-DE", "es", "es-ES", "fr", "fr-FR", "it", "it-IT",
            "uz", "uz-UZ", "kk", "kk-KK");

    private VoiceLanguages() {}

    /** @return the two-letter code, English when the game language is unknown */
    public static String iso639(String gameLanguage) {
        if (gameLanguage == null || gameLanguage.length() < 2) {
            return "en";
        }
        return gameLanguage.substring(0, 2).toLowerCase(Locale.ROOT);
    }

    /** Accepts either a full tag ("ru-RU") or an ISO 639-1 code ("ru"); unsupported values give "". */
    public static String yandexTag(String tag) {
        if (tag == null) {
            return "";
        }
        String value = tag.strip();
        String mapped = YANDEX_ISO.get(value.toLowerCase(Locale.ROOT));
        if (mapped != null) {
            return mapped;
        }
        return YANDEX_TAGS.contains(value) ? value : "";
    }

    /**
     * The language a recognition request should carry. OpenAI-compatible providers detect the
     * language themselves, so {@code auto} becomes an empty string for them; Yandex needs a tag.
     */
    public static String sttLanguage(String providerId, String configured, String gameLanguage) {
        boolean auto = isAuto(configured);
        boolean yandex = "yandex".equals(providerId);
        if (!auto) {
            return yandex ? yandexTag(configured.strip()) : configured.strip();
        }
        if (yandex) {
            return "ru".equals(iso639(gameLanguage)) ? "ru-RU" : "en-US";
        }
        return "";
    }

    /**
     * The language a synthesis request should carry. Only Yandex accepts one; the other providers
     * detect it from the voice or ignore it, so they always get an empty string.
     */
    public static String ttsLanguage(String providerId, String configured, String gameLanguage) {
        if (!"yandex".equals(providerId)) {
            return "";
        }
        return sttLanguage(providerId, configured, gameLanguage);
    }

    private static boolean isAuto(String configured) {
        return configured == null || configured.isBlank() || configured.equalsIgnoreCase("auto");
    }
}
