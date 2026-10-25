package dev.friendai.voice;

/** 16-bit little-endian mono PCM at a known sample rate. */
public record PcmAudio(byte[] data, int sampleRateHz) {
    public PcmAudio {
        data = data == null ? new byte[0] : data;
    }

    /** A copy with every sample multiplied by {@code volume} and clamped to the 16-bit range. */
    public PcmAudio scaled(double volume) {
        return new PcmAudio(PcmConverter.withGain(data, volume), sampleRateHz);
    }
}
