package dev.friendai.client.voice;

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
public final class MicCapture {
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int BYTES_PER_SECOND = 32000;

    private volatile boolean recording;
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
                if (mixerInfo.getName().equals(deviceName) && AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
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
        recording = true;
        int limit = maxSeconds * BYTES_PER_SECOND;
        TargetDataLine source = opened;
        ByteArrayOutputStream sink = buffer;
        reader = new Thread(() -> {
            byte[] chunk = new byte[4096];
            while (recording && sink.size() < limit) {
                int n = source.read(chunk, 0, chunk.length);
                if (n <= 0) {
                    break;
                }
                synchronized (sink) {
                    sink.write(chunk, 0, n);
                }
            }
            recording = false;
        }, "friendai-mic");
        reader.setDaemon(true);
        reader.start();
    }

    /** Stops recording and returns what was captured (empty if nothing was recording). */
    public synchronized byte[] stop() {
        recording = false;
        if (line == null) {
            return new byte[0];
        }
        line.stop();
        line.close();
        try {
            reader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] out;
        synchronized (buffer) {
            out = buffer.toByteArray();
        }
        line = null;
        reader = null;
        buffer = null;
        return out;
    }

    public boolean isRecording() {
        return recording;
    }

    /** True once the recording thread has ended on its own (limit reached) and {@link #stop()} is still due. */
    public synchronized boolean hasPendingRecording() {
        return line != null;
    }
}
