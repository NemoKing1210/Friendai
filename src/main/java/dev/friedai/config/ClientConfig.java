package dev.friedai.config;

/** friedai-client.json. */
public class ClientConfig {
    public int version = 1;
    public VoiceSection voice = new VoiceSection();

    public static class VoiceSection {
        public boolean enabled = true;
        public String whisperModel = "base";
        public String language = "auto";
        public String inputDevice = null;
        public int maxRecordSec = 30;
    }

    public static ClientConfig defaults() {
        return new ClientConfig();
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
    }
}
