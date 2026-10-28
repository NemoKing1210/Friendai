package dev.friendai.voice;

import dev.friendai.config.ClientConfig;
import dev.friendai.infrastructure.config.SecretResolver;
import dev.friendai.voice.stt.SttPresets;
import dev.friendai.voice.tts.TtsPresets;
import dev.friendai.voice.tts.VoiceModel;
import dev.friendai.voice.tts.VoiceModels;
import java.util.Map;
import java.util.function.Function;

/** Turns the player's stored settings into what a protocol adapter and an engine need. */
public final class VoiceConfig {
    private VoiceConfig() {}

    public static ResolvedStt resolveStt(
            ClientConfig.SttSection section, String gameLanguage, Function<String, String> env) {
        VoicePreset preset = SttPresets.require(section.provider);
        if (preset.local()) {
            return new ResolvedStt(
                    preset.id(),
                    preset.protocol(),
                    null,
                    "",
                    section.local == null ? null : section.local.model,
                    "",
                    null);
        }
        ClientConfig.CloudSettings cloud = cloud(section.cloud, preset);
        return new ResolvedStt(
                preset.id(),
                preset.protocol(),
                pick(cloud == null ? null : cloud.baseUrl, preset.defaultBaseUrl()),
                resolveKey(cloud, preset, env),
                pick(cloud == null ? null : cloud.model, preset.defaultModel()),
                VoiceLanguages.sttLanguage(preset.id(), section.language, gameLanguage),
                cloud == null ? null : cloud.folderId);
    }

    public static ResolvedTts resolveTts(
            ClientConfig.TtsSection section, String gameLanguage, Function<String, String> env) {
        VoicePreset preset = TtsPresets.require(section.provider);
        if (preset.local()) {
            String configured = section.local == null ? null : section.local.voice;
            String voice = configured == null || configured.isBlank()
                    ? VoiceModels.defaultFor(gameLanguage).id()
                    : VoiceModels.byId(configured).map(VoiceModel::id).orElse(null);
            double speed = section.local == null ? 1.0 : section.local.speed;
            return new ResolvedTts(preset.id(), preset.protocol(), null, "", null, voice, "", null, speed);
        }
        ClientConfig.CloudSettings cloud = cloud(section.cloud, preset);
        String configuredLanguage = cloud != null && cloud.language != null && !cloud.language.isBlank()
                ? cloud.language
                : section.language;
        double speed = cloud == null || cloud.speed <= 0 ? 1.0 : cloud.speed;
        return new ResolvedTts(
                preset.id(),
                preset.protocol(),
                pick(cloud == null ? null : cloud.baseUrl, preset.defaultBaseUrl()),
                resolveKey(cloud, preset, env),
                pick(cloud == null ? null : cloud.model, preset.defaultModel()),
                pick(cloud == null ? null : cloud.voice, preset.defaultVoice()),
                VoiceLanguages.ttsLanguage(preset.id(), configuredLanguage, gameLanguage),
                cloud == null ? null : cloud.folderId,
                speed);
    }

    /**
     * Identifies the engine a resolved configuration needs, so a changed key is not served by a
     * cached engine built with the old one. The key travels hashed: this string is never shown to a
     * player, but it must not carry a secret either.
     */
    public static String ttsCacheKey(ResolvedTts settings) {
        return settings.providerId() + "|" + settings.baseUrl() + "|" + settings.model() + "|"
                + settings.voice() + "|" + settings.speed() + "|" + settings.language() + "|"
                + settings.folderId() + "|"
                + (settings.hasKey() ? Integer.toHexString(settings.apiKey().hashCode()) : "");
    }

    private static ClientConfig.CloudSettings cloud(Map<String, ClientConfig.CloudSettings> cloud, VoicePreset preset) {
        return cloud == null ? null : cloud.get(preset.id());
    }

    /** An explicit key wins; a blank one falls back to the preset's environment reference. */
    private static String resolveKey(
            ClientConfig.CloudSettings cloud, VoicePreset preset, Function<String, String> env) {
        String configured = cloud == null ? null : cloud.apiKey;
        String reference = configured == null || configured.isBlank() ? preset.defaultApiKey() : configured;
        return SecretResolver.resolve(reference, env);
    }

    /** A pasted value may carry surrounding whitespace; it is stripped before it reaches a URI builder. */
    private static String pick(String configured, String fallback) {
        String value = configured == null ? null : configured.strip();
        return value == null || value.isEmpty() ? fallback : value;
    }
}
