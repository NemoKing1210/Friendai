package dev.friendai.config;

import dev.friendai.voice.stt.SttPresets;
import dev.friendai.voice.tts.TtsPresets;
import java.util.Map;

/**
 * Unsaved speech settings carried across a screen rebuild (provider change, reveal toggle), so a
 * half-filled key is not lost. Each service keeps its values under its own provider, which is what
 * stops one provider's key from moving to another.
 */
public record VoiceDraft(
        String sttProvider, Cloud stt, boolean sttReveal,
        String ttsProvider, Cloud tts, boolean ttsReveal) {

    /** One provider's cloud values. Only the selected provider's entry is written back. */
    public record Cloud(
            String apiKey, String model, String voice, String folderId, String baseUrl, String language) {

        /** Never prints the key (rule 7). */
        @Override
        public String toString() {
            return "Cloud[apiKey=" + (apiKey == null || apiKey.isBlank() ? "" : "***")
                    + ", model=" + model + ", voice=" + voice + ", folderId=" + folderId
                    + ", baseUrl=" + baseUrl + ", language=" + language + "]";
        }
    }

    public static VoiceDraft from(ClientConfig config) {
        return from(config, config.voice.stt.provider, config.voice.tts.provider);
    }

    /**
     * Reads each service's own stored entry for the given providers. A rebuild after a provider
     * change must use this, not {@link #from(ClientConfig)}: the widgets still hold the old
     * provider, so reading the config would pair the new provider with the old provider's values.
     */
    public static VoiceDraft from(ClientConfig config, String sttProvider, String ttsProvider) {
        return new VoiceDraft(
                sttProvider, cloud(config.voice.stt.cloud, sttProvider), false,
                ttsProvider, cloud(config.voice.tts.cloud, ttsProvider), false);
    }

    public VoiceDraft withSttReveal(boolean reveal) {
        return new VoiceDraft(sttProvider, stt, reveal, ttsProvider, tts, ttsReveal);
    }

    public VoiceDraft withTtsReveal(boolean reveal) {
        return new VoiceDraft(sttProvider, stt, sttReveal, ttsProvider, tts, reveal);
    }

    /** Never prints a key (rule 7); {@link Cloud#toString()} redacts it. */
    @Override
    public String toString() {
        return "VoiceDraft[sttProvider=" + sttProvider + ", stt=" + stt + ", sttReveal=" + sttReveal
                + ", ttsProvider=" + ttsProvider + ", tts=" + tts + ", ttsReveal=" + ttsReveal + "]";
    }

    /** Writes the draft into the live config; the save button persists it. */
    public void applyTo(ClientConfig config) {
        String sttId = sttProvider == null || sttProvider.isBlank() ? SttPresets.LOCAL : sttProvider;
        String ttsId = ttsProvider == null || ttsProvider.isBlank() ? TtsPresets.LOCAL : ttsProvider;
        config.voice.stt.provider = sttId;
        config.voice.tts.provider = ttsId;
        // An unknown or local id must not create a cloud entry.
        if (!SttPresets.require(sttId).local()) {
            write(config.voice.stt.cloud, sttId, stt);
        }
        if (!TtsPresets.require(ttsId).local()) {
            write(config.voice.tts.cloud, ttsId, tts);
        }
    }

    private static Cloud cloud(Map<String, ClientConfig.CloudSettings> cloud, String provider) {
        ClientConfig.CloudSettings settings =
                cloud == null || provider == null ? null : cloud.get(provider);
        if (settings == null) {
            return new Cloud(null, null, null, null, null, null);
        }
        return new Cloud(settings.apiKey, settings.model, settings.voice,
                settings.folderId, settings.baseUrl, settings.language);
    }

    private static void write(
            Map<String, ClientConfig.CloudSettings> cloud, String provider, Cloud values) {
        if (values == null) {
            return;
        }
        ClientConfig.CloudSettings settings =
                cloud.computeIfAbsent(provider, id -> new ClientConfig.CloudSettings());
        settings.apiKey = blankToNull(values.apiKey());
        settings.model = blankToNull(values.model());
        settings.voice = blankToNull(values.voice());
        settings.folderId = blankToNull(values.folderId());
        settings.baseUrl = blankToNull(values.baseUrl());
        settings.language = blankToNull(values.language());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
