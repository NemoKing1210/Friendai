package dev.friendai.voice.tts;

import dev.friendai.config.ClientConfig;
import dev.friendai.config.SecretResolver;
import dev.friendai.text.UserMessage;
import java.util.function.Function;

/** The speech settings resolved into what an engine needs: defaults applied and the key looked up. */
public record TtsSettings(
        String provider, String baseUrl, String apiKey, String model, String voice, double speed, double volume) {
    public static TtsSettings from(ClientConfig.TtsSection cfg, String gameLanguage, Function<String, String> env) {
        String provider = cfg.provider == null ? "local" : cfg.provider;
        if ("openai".equals(provider)) {
            TtsProvider preset = TtsPresets.byId("openai").orElseThrow();
            String base = cfg.openai.baseUrl == null || cfg.openai.baseUrl.isBlank()
                    ? TtsPresets.OPENAI_DEFAULT_BASE
                    : cfg.openai.baseUrl.strip();
            return new TtsSettings(
                    "openai",
                    base,
                    SecretResolver.resolve(cfg.openai.apiKey, env),
                    orDefault(cfg.openai.model, preset.defaultModel()),
                    orDefault(cfg.openai.voice, preset.defaultVoice()),
                    cfg.openai.speed,
                    cfg.volume);
        }
        if ("elevenlabs".equals(provider)) {
            TtsProvider preset = TtsPresets.byId("elevenlabs").orElseThrow();
            return new TtsSettings(
                    "elevenlabs",
                    TtsPresets.ELEVENLABS_DEFAULT_BASE,
                    SecretResolver.resolve(cfg.elevenlabs.apiKey, env),
                    orDefault(cfg.elevenlabs.model, preset.defaultModel()),
                    orDefault(cfg.elevenlabs.voice, preset.defaultVoice()),
                    cfg.elevenlabs.speed,
                    cfg.volume);
        }
        String voice = cfg.local == null || cfg.local.voice == null || cfg.local.voice.isBlank()
                ? VoiceModels.defaultFor(gameLanguage).id()
                : cfg.local.voice;
        double speed = cfg.local == null ? 1.0 : cfg.local.speed;
        return new TtsSettings("local", "", "", "", voice, speed, cfg.volume);
    }

    public boolean isLocal() {
        return "local".equals(provider);
    }

    public boolean isOpenAi() {
        return "openai".equals(provider);
    }

    /** A message to show when this configuration cannot synthesize; null when it is usable. */
    public UserMessage readinessError() {
        if (isLocal()) {
            return VoiceModels.byId(voice).isPresent() ? null : UserMessage.of("friendai.voice.tts.error.no_voice");
        }
        boolean customBase = isOpenAi() && !stripSlash(baseUrl).equals(stripSlash(TtsPresets.OPENAI_DEFAULT_BASE));
        if (blank(apiKey) && !customBase) {
            return UserMessage.of("friendai.voice.tts.error.no_key");
        }
        if (blank(voice)) {
            return UserMessage.of("friendai.voice.tts.error.no_voice");
        }
        if (blank(model)) {
            return UserMessage.of("friendai.voice.tts.error.failed");
        }
        return null;
    }

    @Override
    public String toString() {
        return "TtsSettings[provider=" + provider + ", baseUrl=" + baseUrl
                + ", apiKey=" + (blank(apiKey) ? "<none>" : "<redacted>")
                + ", model=" + model + ", voice=" + voice + ", speed=" + speed + ", volume=" + volume + "]";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static String stripSlash(String url) {
        String result = url == null ? "" : url.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
