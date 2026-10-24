package dev.friendai.voice.tts;

/** One downloadable local voice: where it comes from, what it contains and under which license. */
public record VoiceModel(
        String id,
        String displayName,
        String language,
        String url,
        long size,
        String sha256,
        String license,
        String modelFile,
        String tokensFile,
        String dataDir) {}
