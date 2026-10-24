package dev.friendai.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.voice.VoicePreset;
import dev.friendai.voice.stt.SttPresets;
import dev.friendai.voice.tts.TtsPresets;
import dev.friendai.voice.tts.VoiceModels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** friendai-client.json. */
public class ClientConfig {
    public static final int CURRENT_VERSION = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/config");
    private static final List<String> NESTED_TTS_PROVIDERS = List.of("openai", "elevenlabs");

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

    /**
     * Migrates every earlier client config shape to version 3: main's flat v1 shape, the
     * {@code companion-speech} v2 shape (nested provider objects) and the {@code voice-services} v2
     * shape (already {@code stt}/{@code tts} with cloud maps). The shape is detected from the JSON
     * because both branches called their intermediate shape 2. Unknown keys are copied through.
     */
    public static JsonObject migrate(JsonObject json, int fromVersion) {
        if (fromVersion >= CURRENT_VERSION) {
            return json;
        }
        JsonObject voice = json.has("voice") && json.get("voice").isJsonObject()
                ? json.getAsJsonObject("voice")
                : new JsonObject();

        if (!voice.has("stt") || !voice.get("stt").isJsonObject()) {
            voice.add("stt", sttFromLegacyFlat(voice));
            voice.remove("enabled");
            voice.remove("whisperModel");
            voice.remove("language");
            voice.remove("inputDevice");
            voice.remove("maxRecordSec");
        }
        completeStt(voice.getAsJsonObject("stt"));

        JsonObject tts;
        if (voice.has("tts") && voice.get("tts").isJsonObject()) {
            tts = voice.getAsJsonObject("tts");
        } else {
            tts = defaultTts();
            voice.add("tts", tts);
        }
        migrateTts(tts);
        json.add("voice", voice);
        return json;
    }

    /** Adds what a partially written {@code voice-services} v2 section may lack, without touching values. */
    private static void completeStt(JsonObject stt) {
        addIfMissing(stt, "enabled", true);
        addIfMissing(stt, "provider", SttPresets.LOCAL);
        addIfMissing(stt, "language", "auto");
        addIfMissing(stt, "maxRecordSec", 30);
        JsonObject local = stt.has("local") && stt.get("local").isJsonObject()
                ? stt.getAsJsonObject("local")
                : new JsonObject();
        addIfMissing(local, "model", "base");
        stt.add("local", local);
        addIfMissing(stt, "cloud", new JsonObject());
    }

    private static JsonObject sttFromLegacyFlat(JsonObject voice) {
        JsonObject stt = new JsonObject();
        stt.addProperty("enabled", bool(voice, "enabled", true));
        stt.addProperty("provider", SttPresets.LOCAL);
        stt.addProperty("language", string(voice, "language", "auto"));
        JsonElement inputDevice = voice.get("inputDevice");
        if (inputDevice != null && !inputDevice.isJsonNull()) {
            stt.add("inputDevice", inputDevice.deepCopy());
        }
        stt.addProperty("maxRecordSec", number(voice, "maxRecordSec", 30));
        JsonObject local = new JsonObject();
        local.addProperty("model", string(voice, "whisperModel", "base"));
        stt.add("local", local);
        stt.add("cloud", new JsonObject());
        return stt;
    }

    private static JsonObject defaultTts() {
        JsonObject tts = new JsonObject();
        tts.addProperty("enabled", false);
        tts.addProperty("provider", TtsPresets.LOCAL);
        tts.addProperty("volume", 1.0);
        tts.addProperty("maxChars", 300);
        tts.addProperty("speakGuests", false);
        tts.add("local", new JsonObject());
        return tts;
    }

    private static void migrateTts(JsonObject tts) {
        if (!tts.has("cloud") || !tts.get("cloud").isJsonObject()) {
            // The companion-speech shape kept each provider as a nested object.
            JsonObject cloud = new JsonObject();
            for (String provider : NESTED_TTS_PROVIDERS) {
                if (!tts.has(provider) || !tts.get(provider).isJsonObject()) {
                    continue;
                }
                JsonObject nested = tts.getAsJsonObject(provider);
                JsonObject entry = new JsonObject();
                copyIfPresent(nested, entry, "apiKey");
                copyIfPresent(nested, entry, "baseUrl");
                copyIfPresent(nested, entry, "model");
                copyIfPresent(nested, entry, "voice");
                entry.addProperty("speed", positiveNumber(nested, "speed", 1.0));
                cloud.add(provider, entry);
                tts.remove(provider);
            }
            tts.add("cloud", cloud);
        }
        addIfMissing(tts, "enabled", false);
        addIfMissing(tts, "provider", TtsPresets.LOCAL);
        addIfMissing(tts, "volume", 1.0);
        addIfMissing(tts, "maxChars", 300);
        addIfMissing(tts, "speakGuests", false);
        JsonObject local = tts.has("local") && tts.get("local").isJsonObject()
                ? tts.getAsJsonObject("local")
                : new JsonObject();
        stripVoicePrefix(local);
        addIfMissing(local, "speed", 1.0);
        tts.add("local", local);
        addIfMissing(tts, "language", "auto");
        JsonObject cloud = tts.getAsJsonObject("cloud");
        for (Map.Entry<String, JsonElement> entry : cloud.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject settings = entry.getValue().getAsJsonObject();
            if (!settings.has("speed") || settings.get("speed").getAsDouble() <= 0) {
                settings.addProperty("speed", 1.0);
            }
        }
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
        stt.maxRecordSec = Math.max(1, Math.min(120, stt.maxRecordSec));
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
        tts.volume = clamp(tts.volume, 0.0, 1.0);
        tts.maxChars = (int) Math.max(20, Math.min(500, tts.maxChars));
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
            // Gson does not run field initializers, so a missing speed arrives as 0.0.
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
        settings.baseUrl = blankToNull(settings.baseUrl);
        settings.language = blankToNull(settings.language);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void stripVoicePrefix(JsonObject local) {
        JsonElement voice = local.get("voice");
        if (voice == null || !voice.isJsonPrimitive()) {
            return;
        }
        String id = voice.getAsString();
        if (id.startsWith("vits-piper-")) {
            local.addProperty("voice", id.substring("vits-piper-".length()));
        }
    }

    private static void addIfMissing(JsonObject json, String name, boolean fallback) {
        if (!json.has(name)) {
            json.addProperty(name, fallback);
        }
    }

    private static void addIfMissing(JsonObject json, String name, double fallback) {
        if (!json.has(name)) {
            json.addProperty(name, fallback);
        }
    }

    private static void addIfMissing(JsonObject json, String name, String fallback) {
        if (!json.has(name)) {
            json.addProperty(name, fallback);
        }
    }

    private static void addIfMissing(JsonObject json, String name, JsonObject fallback) {
        if (!json.has(name)) {
            json.add(name, fallback);
        }
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String name) {
        JsonElement value = from.get(name);
        if (value != null && !value.isJsonNull()) {
            to.add(name, value.deepCopy());
        }
    }

    private static String string(JsonObject json, String name, String fallback) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String name, boolean fallback) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static int number(JsonObject json, String name, int fallback) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static double positiveNumber(JsonObject json, String name, double fallback) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        double number = value.getAsDouble();
        return number <= 0 ? fallback : number;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
