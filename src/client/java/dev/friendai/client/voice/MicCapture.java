package dev.friendai.client.voice;

import dev.friendai.features.speech.AudioLevel;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.PcmRingBuffer;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int DEFAULT_PRE_ROLL_SECONDS = 2;

    private volatile boolean recording;
    private volatile boolean monitoring;
    private volatile float level;
    /** The failure a listener raised on the capture thread; read by the caller after {@link #stop()}. */
    private volatile RuntimeException listenerFailure;
    /** Two seconds of 16 kHz mono 16-bit audio, never uploaded, overwritten oldest-first. */
    private volatile PcmRingBuffer preRoll = new PcmRingBuffer(DEFAULT_PRE_ROLL_SECONDS * BYTES_PER_SECOND);

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
        start(deviceName, maxSeconds, null);
    }

    /**
     * Records while delivering ordered 16 kHz float chunks to {@code listener} on the capture thread.
     *
     * @param deviceName null means the system default
     * @param listener receives every chunk as it arrives; a thrown failure stops the recording and is reported by
     *     {@link #listenerFailure()}
     */
    public synchronized void start(String deviceName, int maxSeconds, ChunkListener listener)
            throws LineUnavailableException {
        if (recording) {
            return;
        }
        TargetDataLine opened = openLine(deviceName);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        line = opened;
        buffer = sink;
        listenerFailure = null;
        monitoring = false;
        recording = true;
        level = 0f;
        reader = new Thread(() -> read(opened, sink, maxSeconds * BYTES_PER_SECOND, listener, null), "friendai-mic");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Opens the same line for hands-free listening: chunks are delivered as raw PCM and only the bounded pre-roll is
     * kept, so a session that merely listens never accumulates a clip. The caller switches to real capture — or stops
     * — when its detector reports a hit; a clip accumulated here is never uploaded and never persisted.
     *
     * @param preRollSeconds seconds of audio the pre-roll keeps, clamped to 1..5
     */
    public synchronized void startMonitoring(String deviceName, int preRollSeconds, PcmListener listener)
            throws LineUnavailableException {
        if (recording) {
            return;
        }
        int seconds = Math.max(1, Math.min(5, preRollSeconds));
        if (preRoll.capacity() != seconds * BYTES_PER_SECOND) {
            preRoll = new PcmRingBuffer(seconds * BYTES_PER_SECOND);
        }
        TargetDataLine opened = openLine(deviceName);
        line = opened;
        buffer = null;
        listenerFailure = null;
        monitoring = true;
        recording = true;
        level = 0f;
        reader = new Thread(() -> read(opened, null, 0, null, listener), "friendai-mic-monitor");
        reader.setDaemon(true);
        reader.start();
    }

    /** Stops recording and returns what was captured (empty while monitoring, or if nothing was recording). */
    public synchronized byte[] stop() {
        recording = false;
        TargetDataLine closingLine = line;
        Thread closingReader = reader;
        ByteArrayOutputStream closingBuffer = buffer;
        line = null;
        reader = null;
        buffer = null;
        monitoring = false;
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
        if (closingBuffer == null) {
            return new byte[0];
        }
        synchronized (closingBuffer) {
            return closingBuffer.toByteArray();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** A copy of the volatile pre-roll; empty before any recording and never persisted. */
    public byte[] preRoll() {
        return preRoll.snapshot();
    }

    /** True while a clip is being recorded for recognition; a monitoring session does not count. */
    public boolean isRecording() {
        return recording && !monitoring;
    }

    /** True while the microphone is open only to listen for the wake word. */
    public boolean isMonitoring() {
        return recording && monitoring;
    }

    /** The loudness of the last chunk, 0..1; what the HUD's bars follow. */
    public float level() {
        return level;
    }

    /** True once the recording thread has ended on its own (limit reached) and {@link #stop()} is still due. */
    public synchronized boolean hasPendingRecording() {
        return line != null;
    }

    /** The failure a chunk listener raised during the last recording, or null. */
    public RuntimeException listenerFailure() {
        return listenerFailure;
    }

    private TargetDataLine openLine(String deviceName) throws LineUnavailableException {
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
        return opened;
    }

    /**
     * The one capture loop. A clip recording passes a buffer and a {@link ChunkListener}; a monitoring session passes
     * a {@link PcmListener} and no buffer, so it keeps only the pre-roll.
     */
    private void read(
            TargetDataLine source, ByteArrayOutputStream sink, int limit, ChunkListener floats, PcmListener pcm) {
        byte[] chunk = new byte[1024];
        try {
            while (recording) {
                int n = source.read(chunk, 0, chunk.length);
                if (n <= 0) {
                    break;
                }
                level = AudioLevel.of(chunk, n);
                preRoll.write(chunk, n);
                if (sink != null) {
                    synchronized (sink) {
                        if (sink.size() >= limit) {
                            break;
                        }
                        sink.write(chunk, 0, n);
                    }
                }
                if (floats != null) {
                    try {
                        floats.onChunk(PcmConverter.toFloat(Arrays.copyOf(chunk, n)));
                    } catch (RuntimeException failure) {
                        // The recognizer refused the chunk (overflow or a closed session): stop capture and let the
                        // caller report the typed failure after release.
                        listenerFailure = failure;
                        recording = false;
                        break;
                    }
                }
                if (pcm != null) {
                    try {
                        pcm.onPcm(chunk, n);
                    } catch (RuntimeException failure) {
                        listenerFailure = failure;
                        recording = false;
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Closing a line during disconnect can interrupt a blocking read.
        } finally {
            recording = false;
        }
    }

    /** Receives ordered 16 kHz mono samples on the capture thread. */
    @FunctionalInterface
    public interface ChunkListener {
        void onChunk(float[] samples16k);
    }

    /** Receives ordered 16 kHz 16-bit mono PCM on the capture thread; the array is reused after the call returns. */
    @FunctionalInterface
    public interface PcmListener {
        void onPcm(byte[] pcm16le, int length);
    }
}
