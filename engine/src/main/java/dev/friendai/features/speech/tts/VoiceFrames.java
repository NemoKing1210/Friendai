package dev.friendai.features.speech.tts;

import dev.friendai.features.speech.PcmResampler;

/**
 * A clip cut into the fixed frames a voice-chat channel accepts: 48 kHz mono, exactly {@value #FRAME_SAMPLES}
 * samples (20 ms) each. A channel ignores a frame of any other length, so the tail is padded with silence.
 */
public final class VoiceFrames {
    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_SAMPLES = 960;
    /** How long one frame sounds; a sender hands frames over at this interval. */
    public static final long FRAME_NANOS = 20_000_000L;

    private static final VoiceFrames EMPTY = new VoiceFrames(new short[0]);

    private final short[] samples;
    private final int count;

    private VoiceFrames(short[] samples) {
        this.samples = samples;
        this.count = (samples.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES;
    }

    /**
     * Resamples the clip to 48 kHz and applies {@code gain}, clamping instead of wrapping. A clip without a whole
     * sample has no frames; a muted clip keeps its frames, so it still takes its time to play.
     *
     * @throws IllegalArgumentException when the clip's sample rate cannot be converted
     */
    public static VoiceFrames of(TtsClip clip, double gain) {
        if (clip == null || clip.pcm16le() == null || clip.pcm16le().length < 2) {
            return EMPTY;
        }
        short[] samples = PcmResampler.resample(PcmResampler.toShorts(clip.pcm16le()), clip.sampleRate(), SAMPLE_RATE);
        if (gain != 1.0) {
            for (int i = 0; i < samples.length; i++) {
                long scaled = Math.round(samples[i] * gain);
                samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            }
        }
        return new VoiceFrames(samples);
    }

    public int count() {
        return count;
    }

    /** A fresh array of exactly {@value #FRAME_SAMPLES} samples; the last frame ends in silence. */
    public short[] frame(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("frame " + index + " of " + count);
        }
        short[] frame = new short[FRAME_SAMPLES];
        int offset = index * FRAME_SAMPLES;
        System.arraycopy(samples, offset, frame, 0, Math.min(FRAME_SAMPLES, samples.length - offset));
        return frame;
    }
}
