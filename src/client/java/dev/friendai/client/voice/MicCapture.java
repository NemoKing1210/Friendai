package dev.friendai.client.voice;

import dev.friendai.features.speech.AudioLevel;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** Records 16 kHz, 16-bit, mono, little-endian PCM from a microphone. */
public final class MicCapture implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int BYTES_PER_SECOND = 32000;

    private volatile boolean recording;
    private volatile float level;
    private TargetDataLine line;
    private Thread reader;
    private ByteArrayOutputStream buffer;

    /** Names of the audio inputs that can record in our format. */
    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                names.add(mixerInfo.getName());
            }
        }
        return names;
    }

    /** @param deviceName null means the system default */
    public synchronized void start(String deviceName, int maxSeconds) throws LineUnavailableException {
        if (recording) {
            return;
        }
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        TargetDataLine opened = null;
        if (deviceName != null && !deviceName.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().equals(deviceName)
                        && AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    opened = (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
                    break;
                }
            }
        }
        if (opened == null) {
            opened = AudioSystem.getTargetDataLine(FORMAT);
        }
        opened.open(FORMAT);
        opened.start();
        line = opened;
        buffer = new ByteArrayOutputStream();
        level = 0f;
        recording = true;
        int limit = maxSeconds * BYTES_PER_SECOND;
        TargetDataLine source = opened;
        ByteArrayOutputStream sink = buffer;
        reader = new Thread(
                () -> {
                    byte[] chunk = new byte[1024];
                    try {
                        while (recording && sink.size() < limit) {
                            int n = source.read(chunk, 0, chunk.length);
                            if (n <= 0) {
                                break;
                            }
                            level = AudioLevel.of(chunk, n);
                            synchronized (sink) {
                                sink.write(chunk, 0, n);
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // Closing a line during disconnect can interrupt a blocking read.
                    } finally {
                        recording = false;
                    }
                },
                "friendai-mic");
        reader.setDaemon(true);
        reader.start();
    }

    /** Stops recording and returns what was captured (empty if nothing was recording). */
    public synchronized byte[] stop() {
        recording = false;
        TargetDataLine closingLine = line;
        Thread closingReader = reader;
        ByteArrayOutputStream closingBuffer = buffer;
        line = null;
        reader = null;
        buffer = null;
        level = 0f;
        if (closingLine == null) {
            return new byte[0];
        }
        try {
            closingLine.stop();
        } catch (RuntimeException ignored) {
            // The audio provider may have stopped the line while disconnecting.
        }
        try {
            closingLine.close();
        } catch (RuntimeException ignored) {
            // Continue joining the reader even when the device reports an already-closed line.
        }
        try {
            if (closingReader != null) closingReader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] out;
        synchronized (closingBuffer) {
            out = closingBuffer.toByteArray();
        }
        return out;
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRecording() {
        return recording;
    }

    /** The loudness of the last chunk, 0..1; what the HUD's bars follow. */
    public float level() {
        return level;
    }

    /** True once the recording thread has ended on its own (limit reached) and {@link #stop()} is still due. */
    public synchronized boolean hasPendingRecording() {
        return line != null;
    }
}
