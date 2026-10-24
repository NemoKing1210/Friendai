package dev.friendai.client.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmConverter;
import dev.friendai.voice.tts.TtsClip;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays 16-bit mono PCM at the clip's own sample rate. The volume is applied here, once, so no engine has to
 * know about it.
 */
public final class SpeechPlayback {
    private final Object lock = new Object();
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
        if (clip == null || clip.pcm16le().length == 0) {
            return;
        }
        AudioFormat format = new AudioFormat(clip.sampleRate(), 16, 1, true, false);
        SourceDataLine opened;
        try {
            opened = open(format, deviceName);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.playback"));
        }
        synchronized (lock) {
            stopLocked();
            line = opened;
        }
        byte[] pcm = PcmConverter.withGain(clip.pcm16le(), volume);
        try {
            opened.start();
            int offset = 0;
            while (offset < pcm.length) {
                synchronized (lock) {
                    if (line != opened) {
                        return;
                    }
                }
                int written = opened.write(pcm, offset, Math.min(4096, pcm.length - offset));
                if (written <= 0) {
                    break;
                }
                offset += written;
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

    private static SourceDataLine open(AudioFormat format, String deviceName) throws LineUnavailableException {
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
        opened.open(format);
        return opened;
    }
}
