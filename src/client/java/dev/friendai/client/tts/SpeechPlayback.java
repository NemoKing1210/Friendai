package dev.friendai.client.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmConverter;
import dev.friendai.voice.SpatialAudio;
import dev.friendai.voice.tts.PlaybackGate;
import dev.friendai.voice.tts.TtsClip;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays 16-bit PCM at the clip's own sample rate, mono or stereo. The volume and, for a spatial reply,
 * the left/right mix and distance are applied here, so no engine has to know about them.
 */
public final class SpeechPlayback {
    /** Audio per block: 20 ms, so a pan follows a head turn without audible steps. */
    private static final int BLOCKS_PER_SECOND = 50;
    /** Blocks the device buffers: a small buffer is what keeps the pan from being heard late. */
    private static final int BUFFER_BLOCKS = 5;

    private final Object lock = new Object();
    private final PlaybackGate gate = new PlaybackGate();
    private SourceDataLine line;

    /** Names of the audio outputs that can play what we synthesize. */
    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, null);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                names.add(mixerInfo.getName());
            }
        }
        return names;
    }

    /** Blocks until the clip has played or {@link #stop()} closed the line under it. */
    public void play(TtsClip clip, double volume, String deviceName) {
        play(clip, volume, deviceName, null);
    }

    /**
     * Blocks until the clip has played or {@link #stop()} closed the line under it. With a spatial
     * source the clip is mixed to stereo one short block at a time, ramping each channel from the
     * previous block's gains to the current ones so the pan follows the player smoothly, and the line
     * is opened with a small buffer so the change is heard without the device's default delay. A device
     * that cannot open stereo still gets the distance attenuation through a mono fallback.
     */
    public void play(TtsClip clip, double volume, String deviceName, Supplier<SpatialAudio.Mix> spatial) {
        if (clip == null || clip.pcm16le().length == 0) {
            return;
        }
        boolean monoFallback = false;
        long token = gate.token();
        int blockSamples = Math.max(1, clip.sampleRate() / BLOCKS_PER_SECOND);
        int blockBytes = blockSamples * 2;
        AudioFormat mono = new AudioFormat(clip.sampleRate(), 16, 1, true, false);
        SourceDataLine opened;
        try {
            opened = open(spatial == null ? mono : new AudioFormat(clip.sampleRate(), 16, 2, true, false),
                    deviceName, blockSamples * (spatial == null ? 2 : 4) * BUFFER_BLOCKS);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            if (spatial == null) {
                throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.playback"));
            }
            try {
                opened = open(mono, deviceName, blockSamples * 2 * BUFFER_BLOCKS);
                monoFallback = true;
            } catch (LineUnavailableException | IllegalArgumentException monoError) {
                throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.playback"));
            }
        }
        synchronized (lock) {
            // The device can take a moment to open; a stop during that window must not let the clip play.
            if (!gate.stillOpen(token)) {
                closeQuietly(opened);
                return;
            }
            stopLocked();
            line = opened;
        }
        byte[] pcm = spatial == null ? PcmConverter.withGain(clip.pcm16le(), volume) : clip.pcm16le();
        // The gains of the block written last; the next block ramps from them to the current mix.
        double left = 0.0;
        double right = 0.0;
        double level = 1.0;
        if (spatial != null) {
            SpatialAudio.Mix first = spatial.get();
            if (first == null) {
                first = SpatialAudio.CENTER;
            }
            left = first.left() * volume;
            right = first.right() * volume;
            level = first.level() * volume;
        }
        try {
            opened.start();
            int offset = 0;
            while (offset < pcm.length) {
                synchronized (lock) {
                    if (line != opened) {
                        return;
                    }
                }
                int length = Math.min(blockBytes, pcm.length - offset) & ~1;
                if (length == 0) {
                    // A stray trailing byte cannot form a sample.
                    break;
                }
                int written;
                if (spatial == null) {
                    written = writeAll(opened, pcm, offset, length);
                } else {
                    SpatialAudio.Mix mix = spatial.get();
                    if (mix == null) {
                        mix = SpatialAudio.CENTER;
                    }
                    byte[] block;
                    if (monoFallback) {
                        double next = mix.level() * volume;
                        block = PcmConverter.withGainRamped(pcm, offset, length, level, next);
                        level = next;
                    } else {
                        double nextLeft = mix.left() * volume;
                        double nextRight = mix.right() * volume;
                        block = PcmConverter.toStereoRamped(
                                pcm, offset, length, left, right, nextLeft, nextRight);
                        left = nextLeft;
                        right = nextRight;
                    }
                    written = writeAll(opened, block, 0, block.length);
                }
                offset += length;
                if (written <= 0) {
                    break;
                }
            }
            opened.drain();
        } catch (IllegalStateException e) {
            // Stopped from another thread: the line was closed under us.
        } finally {
            synchronized (lock) {
                if (line == opened) {
                    line = null;
                }
            }
            try {
                opened.stop();
            } catch (IllegalStateException ignored) {
                // already closed by stop()
            }
            opened.close();
        }
    }

    /** Cuts the current clip off. Safe to call when nothing is playing. */
    public void stop() {
        synchronized (lock) {
            gate.cancel();
            stopLocked();
        }
    }

    public void close() {
        stop();
    }

    private void stopLocked() {
        SourceDataLine current = line;
        line = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
            current.flush();
        } catch (IllegalStateException ignored) {
            // the writer already closed it
        }
        current.close();
    }

    /** Writes the whole range, so a partial device write cannot drop a sample in the middle of it. */
    private static int writeAll(SourceDataLine line, byte[] bytes, int offset, int length) {
        int total = 0;
        while (total < length) {
            int written = line.write(bytes, offset + total, length - total);
            if (written <= 0) {
                return total;
            }
            total += written;
        }
        return total;
    }

    private static void closeQuietly(SourceDataLine line) {
        try {
            line.close();
        } catch (IllegalStateException ignored) {
            // never opened: nothing to release
        }
    }

    private static SourceDataLine open(AudioFormat format, String deviceName, int bufferSize)
            throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine opened = null;
        if (deviceName != null && !deviceName.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().equals(deviceName)
                        && AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    opened = (SourceDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
                    break;
                }
            }
        }
        if (opened == null) {
            opened = AudioSystem.getSourceDataLine(format);
        }
        try {
            opened.open(format, bufferSize);
        } catch (IllegalArgumentException unsupportedBufferSize) {
            // A device that refuses the size still plays, just with its own (longer) delay.
            opened.open(format);
        }
        return opened;
    }
}
