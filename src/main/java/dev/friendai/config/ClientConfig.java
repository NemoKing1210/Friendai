package dev.friendai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** friendai-client.json. */
public class ClientConfig {
    public static final int CURRENT_VERSION = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/config");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Set<String> PROVIDERS = Set.of("local", "openai", "elevenlabs");

    public int version = CURRENT_VERSION;
    public VoiceSection voice = new VoiceSection();

    public static class VoiceSection {
        public boolean enabled = true;
        public String whisperModel = "base";
        public String language = "auto";
        public String inputDevice = null;
        public int maxRecordSec = 30;
        public TtsSection tts = new TtsSection();
    }

    /** Speech output. Disabled by default: nothing is spoken until the player turns it on. */
    public static class TtsSection {
        public boolean enabled = false;
        public String provider = "local";
        public double volume = 1.0;
        public int maxChars = 300;
        public boolean speakGuests = false;
        public String outputDevice = null;
        public LocalTts local = new LocalTts();
        public OpenAiTts openai = new OpenAiTts();
        public ElevenLabsTts elevenlabs = new ElevenLabsTts();
    }

    public static class LocalTts {
        /** Null means the language default voice. */
        public String voice = null;
        public double speed = 1.0;
    }

    public static class OpenAiTts {
        public String apiKey = null;
        /** Null means the official OpenAI endpoint. */
        public String baseUrl = null;
        public String model = "gpt-4o-mini-tts";
        public String voice = "alloy";
        public double speed = 1.0;
    }

    public static class ElevenLabsTts {
        public String apiKey = null;
        public String model = "eleven_multilingual_v2";
        /** Null means the preset's default voice. */
        public String voice = null;
        public double speed = 1.0;
    }

    public static ClientConfig defaults() {
        return new ClientConfig();
    }

    /** Adds the fields a v1 config could not have. Gson leaves a missing object null, so this fills it. */
    public static JsonObject migrate(JsonObject json, int from) {
        if (from < 2) {
            JsonObject voice = json.has("voice") && json.get("voice").isJsonObject()
                    ? json.getAsJsonObject("voice")
                    : new JsonObject();
            if (!voice.has("tts") || !voice.get("tts").isJsonObject()) {
                voice.add("tts", GSON.toJsonTree(new TtsSection()));
            }
            if (!json.has("voice")) {
                json.add("voice", voice);
            }
        }
        return json;
    }

    /** Fills missing sections and clamps ranges. */
    public void validate() {
        if (voice == null) {
            voice = new VoiceSection();
        }
        voice.maxRecordSec = Math.max(1, Math.min(120, voice.maxRecordSec));
        if (voice.whisperModel == null || voice.whisperModel.isBlank()) {
            voice.whisperModel = "base";
        }
        if (voice.language == null || voice.language.isBlank()) {
            voice.language = "auto";
        }
        if (voice.tts == null) {
            voice.tts = new TtsSection();
        }
        validateTts(voice.tts);
    }

    private static void validateTts(TtsSection tts) {
        if (tts.provider == null || !PROVIDERS.contains(tts.provider)) {
            if (tts.provider != null) {
                LOGGER.warn("Unknown speech provider '{}'; using local speech", tts.provider);
            }
            tts.provider = "local";
        }
        tts.volume = clamp(tts.volume, 0.0, 1.0);
        tts.maxChars = (int) Math.max(20, Math.min(500, tts.maxChars));
        if (tts.local == null) {
            tts.local = new LocalTts();
        }
        tts.local.speed = clamp(tts.local.speed, 0.5, 2.0);
        if (tts.openai == null) {
            tts.openai = new OpenAiTts();
        }
        tts.openai.speed = clamp(tts.openai.speed, 0.5, 2.0);
        if (tts.openai.model == null || tts.openai.model.isBlank()) {
            tts.openai.model = new OpenAiTts().model;
        }
        if (tts.openai.voice == null || tts.openai.voice.isBlank()) {
            tts.openai.voice = new OpenAiTts().voice;
        }
        if (tts.elevenlabs == null) {
            tts.elevenlabs = new ElevenLabsTts();
        }
        tts.elevenlabs.speed = clamp(tts.elevenlabs.speed, 0.7, 1.2);
        if (tts.elevenlabs.model == null || tts.elevenlabs.model.isBlank()) {
            tts.elevenlabs.model = new ElevenLabsTts().model;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
