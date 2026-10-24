package dev.friendai.voice.tts;

import dev.friendai.text.UserMessage;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceConfig;
import dev.friendai.voice.VoiceReadiness;

/**
 * The speech settings an engine needs: defaults applied and the key looked up.
 *
 * <p>Temporary bridge over {@link ResolvedTts} so the existing engines and the settings screen keep
 * working while the runtime is replaced; the speech runtime resolves through {@link VoiceConfig}.
 */
public record TtsSettings(
        String provider, String protocol, String baseUrl, String apiKey,
        String model, String voice, String language, String folderId, double speed, double volume) {

    public static TtsSettings from(ResolvedTts settings, double volume) {
        return new TtsSettings(
                settings.providerId(), settings.protocol(), settings.baseUrl(), settings.apiKey(),
                settings.model(), settings.voice(), settings.language(), settings.folderId(),
                settings.speed(), volume);
    }

    public boolean isLocal() {
        return "local".equals(provider);
    }

    public boolean isOpenAi() {
        return "openai".equals(provider);
    }

    /** A message to show when this configuration cannot synthesize; null when it is usable. */
    public UserMessage readinessError() {
        return VoiceReadiness.tts(resolved(), VoiceModels.byId(voice).isPresent());
    }

    /** The engine cache key; it identifies the settings without carrying the key. */
    public String cacheKey() {
        return VoiceConfig.ttsCacheKey(resolved());
    }

    private ResolvedTts resolved() {
        return new ResolvedTts(provider, protocol, baseUrl, apiKey, model, voice, language, folderId, speed);
    }

    @Override
    public String toString() {
        return "TtsSettings[provider=" + provider + ", protocol=" + protocol + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<none>" : "<redacted>")
                + ", model=" + model + ", voice=" + voice + ", language=" + language
                + ", folderId=" + folderId + ", speed=" + speed + ", volume=" + volume + "]";
    }
}
