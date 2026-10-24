package dev.friendai.voice.tts;

/** A cloud speech provider preset, as data. */
public record TtsProvider(
        String id,
        String displayName,
        String defaultBaseUrl,
        TtsAuth auth,
        String defaultModel,
        String defaultVoice,
        String docsUrl,
        boolean paid,
        boolean editableBaseUrl) {}
