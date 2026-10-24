package dev.friendai.voice.tts;

/** One synthesized utterance: signed 16-bit mono little-endian PCM at {@code sampleRate}. */
public record TtsClip(byte[] pcm16le, int sampleRate) {}
