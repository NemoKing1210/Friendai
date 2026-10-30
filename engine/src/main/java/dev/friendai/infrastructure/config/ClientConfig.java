package dev.friendai.infrastructure.config;

import dev.friendai.features.speech.VoicePreset;
import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.TtsPresets;
import dev.friendai.features.speech.tts.VoiceModels;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** friendai-client.json. */
public class ClientConfig {
    public static final int CURRENT_VERSION = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/config");
    public int version = CURRENT_VERSION;
    public VoiceSection voice = new VoiceSection();

    public static class VoiceSection {
        public SttSection stt = new SttSection();
        public TtsSection tts = new TtsSection();
    }

    /** Speech recognition: one local Whisper model or one cloud preset at a time. */
    public static class SttSection {
        public boolean enabled = true;
        public String provider = SttPresets.LOCAL;
        public String language = "auto";
        public String inputDevice = null;
        public int maxRecordSec = 30;
        public LocalStt local = new LocalStt();
        public Map<String, CloudSettings> cloud = new LinkedHashMap<>();
    }

    public static class LocalStt {
        public String model = "base";
    }

    /** Speech synthesis. Disabled by default: nothing is spoken until the player turns it on. */
    public static class TtsSection {
        public boolean enabled = false;
        public String provider = TtsPresets.LOCAL;
        public String language = "auto";
        public double volume = 1.0;
        public int maxChars = 300;
        public boolean speakGuests = false;
        /** Replies come from the companion's position; off plays them centred at full volume. */
        public boolean spatial = true;

        public String outputDevice = null;
        public LocalTts local = new LocalTts();
        public Map<String, CloudSettings> cloud = new LinkedHashMap<>();
    }

    public static class LocalTts {
        /** Null means the language default voice. */
        public String voice = null;

        public double speed = 1.0;
    }

    /** One provider's own settings. Entries are never shared between providers. */
    public static class CloudSettings {
        public String apiKey;
        public String model;
        public String voice;
        public String folderId;
        public String baseUrl;
        public String language;
        public double speed = 1.0;
    }

    public static ClientConfig defaults() {
        return new ClientConfig();
    }

    /** Fills missing or invalid sections and clamps ranges. */
    public void validate() {
        if (voice == null) {
            voice = new VoiceSection();
        }
        if (voice.stt == null) {
            voice.stt = new SttSection();
        }
        if (voice.tts == null) {
            voice.tts = new TtsSection();
        }
        validateStt(voice.stt);
        validateTts(voice.tts);
    }

    private static void validateStt(SttSection stt) {
        if (stt.provider == null || SttPresets.byId(stt.provider).isEmpty()) {
            if (stt.provider != null) {
                LOGGER.warn("Unknown speech recognition provider '{}'; using the local model", stt.provider);
            }
            stt.provider = SttPresets.LOCAL;
        }
        if (stt.local == null) {
            stt.local = new LocalStt();
        }
        if (stt.local.model == null || stt.local.model.isBlank()) {
            stt.local.model = "base";
        }
        if (stt.language == null || stt.language.isBlank()) {
            stt.language = "auto";
        }
        // A stored or hand-edited 0 means the field was never set, not the clamping minimum.
        stt.maxRecordSec = stt.maxRecordSec <= 0 ? 30 : Math.min(120, stt.maxRecordSec);
        if (stt.cloud == null) {
            stt.cloud = new LinkedHashMap<>();
        }
        for (VoicePreset preset : SttPresets.all()) {
            if (!preset.local()) {
                stt.cloud.computeIfAbsent(preset.id(), id -> cloudDefaults(preset));
            }
        }
        for (Map.Entry<String, CloudSettings> entry : stt.cloud.entrySet()) {
            CloudSettings settings = entry.getValue();
            if (settings == null) {
                settings = new CloudSettings();
                entry.setValue(settings);
            }
            if (settings.speed <= 0) {
                settings.speed = 1.0;
            }
            blankToNull(settings);
        }
    }

    private static void validateTts(TtsSection tts) {
        if (tts.provider == null || TtsPresets.byId(tts.provider).isEmpty()) {
            if (tts.provider != null) {
                LOGGER.warn("Unknown speech provider '{}'; using local speech", tts.provider);
            }
            tts.provider = TtsPresets.LOCAL;
        }
        // Volume 0 is a real mute, not an unset field: the slider offers it and a mute is useful.
        tts.volume = clamp(tts.volume, 0.0, 1.0);
        tts.maxChars = tts.maxChars <= 0 ? 300 : Math.max(20, Math.min(500, tts.maxChars));
        if (tts.language == null || tts.language.isBlank()) {
            tts.language = "auto";
        }
        if (tts.local == null) {
            tts.local = new LocalTts();
        }
        if (tts.local.voice != null && VoiceModels.byId(tts.local.voice).isEmpty()) {
            // Validated against the catalog: a stale name falls back to the language default.
            tts.local.voice = null;
        }
        tts.local.speed = clampSpeed(TtsPresets.LOCAL, tts.local.speed <= 0 ? 1.0 : tts.local.speed);
        if (tts.cloud == null) {
            tts.cloud = new LinkedHashMap<>();
        }
        for (VoicePreset preset : TtsPresets.all()) {
            if (!preset.local()) {
                tts.cloud.computeIfAbsent(preset.id(), id -> cloudDefaults(preset));
            }
        }
        for (Map.Entry<String, CloudSettings> entry : tts.cloud.entrySet()) {
            CloudSettings settings = entry.getValue();
            if (settings == null) {
                settings = new CloudSettings();
                entry.setValue(settings);
            }
            // An explicit 0 (hand-edited or written by older code) means the default speed.
            settings.speed = clampSpeed(entry.getKey(), settings.speed <= 0 ? 1.0 : settings.speed);
            blankToNull(settings);
        }
    }

    private static CloudSettings cloudDefaults(VoicePreset preset) {
        CloudSettings settings = new CloudSettings();
        settings.apiKey = preset.defaultApiKey();
        settings.model = preset.defaultModel();
        settings.voice = preset.defaultVoice();
        settings.speed = 1.0;
        return settings;
    }

    private static double clampSpeed(String provider, double speed) {
        return switch (provider) {
            case "elevenlabs" -> clamp(speed, 0.7, 1.2);
            case "yandex" -> clamp(speed, 0.1, 3.0);
            default -> clamp(speed, 0.5, 2.0);
        };
    }

    private static void blankToNull(CloudSettings settings) {
        settings.apiKey = blankToNull(settings.apiKey);
        settings.model = blankToNull(settings.model);
        settings.voice = blankToNull(settings.voice);
        settings.folderId = blankToNull(settings.folderId);
        String baseUrl = blankToNull(settings.baseUrl);
        settings.baseUrl = baseUrl == null ? null : baseUrl.strip();
        settings.language = blankToNull(settings.language);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
