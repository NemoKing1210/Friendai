package dev.friendai.features.speech;

import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.TtsPresets;
import dev.friendai.foundation.text.UserMessage;

/** Tells the player why a resolved speech configuration cannot run, or null when it can. */
public final class VoiceReadiness {
    private VoiceReadiness() {}

    /** @param localModelInstalled whether the local Whisper model is downloaded */
    public static UserMessage stt(ResolvedStt settings, boolean localModelInstalled) {
        if (settings.local()) {
            return localModelInstalled ? null : UserMessage.of("friendai.voice.error.no_model");
        }
        VoicePreset preset = SttPresets.require(settings.providerId());
        if (!settings.hasKey() && sameBase(settings.baseUrl(), preset.defaultBaseUrl())) {
            return UserMessage.of("friendai.voice.error.no_key");
        }
        if (preset.uses(CloudField.MODEL) && blank(settings.model())) {
            return UserMessage.of("friendai.voice.error.failed");
        }
        if ("yandex".equals(preset.id()) && blank(settings.language())) {
            return UserMessage.of("friendai.voice.error.unsupported_language");
        }
        // Yandex rejects Api-Key requests without a folder, so catch it before recording starts.
        if ("yandex".equals(preset.id()) && blank(settings.folderId())) {
            return UserMessage.of("friendai.voice.error.no_folder");
        }
        return null;
    }

    /** @param localVoiceInstalled whether the selected local voice is downloaded */
    public static UserMessage tts(ResolvedTts settings, boolean localVoiceInstalled) {
        if (settings.local()) {
            return localVoiceInstalled ? null : UserMessage.of("friendai.voice.tts.error.no_voice");
        }
        VoicePreset preset = TtsPresets.require(settings.providerId());
        if (!settings.hasKey() && sameBase(settings.baseUrl(), preset.defaultBaseUrl())) {
            return UserMessage.of("friendai.voice.tts.error.no_key");
        }
        if (preset.uses(CloudField.VOICE) && blank(settings.voice())) {
            return UserMessage.of("friendai.voice.tts.error.no_voice");
        }
        if (preset.uses(CloudField.MODEL) && blank(settings.model())) {
            return UserMessage.of("friendai.voice.tts.error.failed");
        }
        if ("yandex".equals(preset.id()) && blank(settings.language())) {
            return UserMessage.of("friendai.voice.error.unsupported_language");
        }
        // Yandex rejects Api-Key requests without a folder here too, not only for recognition.
        if ("yandex".equals(preset.id()) && blank(settings.folderId())) {
            return UserMessage.of("friendai.voice.error.no_folder");
        }
        return null;
    }

    /** A custom base URL may legitimately run without a key (a local or self-hosted server). */
    private static boolean sameBase(String actual, String presetBase) {
        return stripSlash(actual).equalsIgnoreCase(stripSlash(presetBase));
    }

    private static String stripSlash(String url) {
        String result = url == null ? "" : url.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
