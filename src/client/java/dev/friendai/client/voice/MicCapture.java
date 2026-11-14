package dev.friendai.client.voice;

import dev.friendai.features.speech.AudioLevel;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.PcmRingBuffer;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Records 16 kHz, 16-bit, mono, little-endian PCM from a microphone.
 *
 * <p>Every capture is its own object: it has the token that identifies it, its own level, pre-roll, clip buffer and
 * listener, and its own completion. A reader that is still returning from a blocking read after a stop writes only
 * into the capture it was started for, so it cannot publish a level, a pre-roll chunk or a clip into the next one.
 *
 * <p>The device is opened, closed and its reader joined on one capture thread, never on the caller's. {@link #start}
 * and {@link #stop} return at once; {@link Capture#opened()} reports a device that refused to open, and the future
 * from {@link #stop} carries the clip once the reader has exited. One capture owns the device at a time: a start
 * while one is open returns {@code null}, and a token can only stop the capture it was handed out for.
 */
public final class MicCapture implements AutoCloseable {
    /** How long the capture thread waits for a reader after closing the line, only to keep the tail chunk. */
    private static final long READER_JOIN_MILLIS = 200;

    /** One capture; only the token that is current may stop the line. */
    public static final class Capture {
        private final Session session;
        private final long token;

        private Capture(Session session, long token) {
            this.session = session;
            this.token = token;
        }

        /** Completes when the device is open, or exceptionally when it could not be opened. */
        public CompletableFuture<Void> opened() {
            return session.opened;
        }

        /** The failure a chunk listener raised during this capture, or null. Read after {@link #stop}. */
        public RuntimeException failure() {
            return session.listenerFailure;
        }
    }

    /** The device operations a capture needs; the seam a test uses to supply a scripted line. */
    interface MicLine {
        int read(byte[] buffer, int offset, int length);

        void start();

        void stop();

        void close();
    }

    /** Opens a line for the device; production wraps a {@link TargetDataLine}. */
    interface LineFactory {
        MicLine open(String deviceName) throws LineUnavailableException;
    }

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int BYTES_PER_SECOND = 32000;
    private static final int DEFAULT_PRE_ROLL_SECONDS = 2;

    /** Everything one capture owns. The reader thread touches only this object. */
    private static final class Session {
        final long token;
        final boolean monitoring;
        final ByteArrayOutputStream buffer;
        final ChunkListener chunks;
        final PcmListener pcm;
        final int limit;
        final CompletableFuture<Void> opened = new CompletableFuture<>();
        final CompletableFuture<byte[]> finished = new CompletableFuture<>();
        volatile PcmRingBuffer preRoll;
        volatile MicLine line;
        volatile Thread reader;
        volatile boolean stopped;
        volatile float level;
        volatile RuntimeException listenerFailure;

        Session(
                long token,
                boolean monitoring,
                ByteArrayOutputStream buffer,
                ChunkListener chunks,
                PcmListener pcm,
                int limit,
                PcmRingBuffer preRoll) {
            this.token = token;
            this.monitoring = monitoring;
            this.buffer = buffer;
            this.chunks = chunks;
            this.pcm = pcm;
            this.limit = limit;
            this.preRoll = preRoll;
        }
    }

    private final Object lock = new Object();
    private final LineFactory lines;
    private final ExecutorService device;
    private Session current;
    private long nextToken;
    private boolean closed;
    private volatile RuntimeException lastFailure;

    public MicCapture() {
        this(MicCapture::openRealLine);
    }

    /** A capture whose device operations run on one daemon thread; {@code lines} may be a test seam. */
    MicCapture(LineFactory lines) {
        this.lines = lines;
        this.device = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "friendai-mic-device");
            thread.setDaemon(true);
            return thread;
        });
    }

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
    public Capture start(String deviceName, int maxSeconds) {
        return start(deviceName, maxSeconds, null);
    }

    /**
     * Records while delivering ordered 16 kHz float chunks to {@code listener} on the capture thread.
     *
     * @param deviceName null means the system default
     * @param listener receives every chunk as it arrives; a thrown failure stops the recording and is reported by
     *     {@link Capture#failure()}
     * @return the capture, or null while another one owns the device
     */
    public Capture start(String deviceName, int maxSeconds, ChunkListener listener) {
        Session session = begin(false, new ByteArrayOutputStream(), maxSeconds, listener, null, null);
        if (session == null) {
            return null;
        }
        device.execute(() -> open(session, deviceName, maxSeconds));
        return new Capture(session, session.token);
    }

    /**
     * Opens the same line for hands-free listening: chunks are delivered as raw PCM and only the bounded pre-roll is
     * kept, so a session that merely listens never accumulates a clip. The caller switches to real capture — or stops
     * — when its detector reports a hit; a clip accumulated here is never uploaded and never persisted.
     *
     * @param preRollSeconds seconds of audio the pre-roll keeps, clamped to 1..5
     * @return the capture, or null while another one owns the device
     */
    public Capture startMonitoring(String deviceName, int preRollSeconds, PcmListener listener) {
        int seconds = Math.max(1, Math.min(5, preRollSeconds));
        Session session = begin(true, null, 0, null, listener, new PcmRingBuffer(seconds * BYTES_PER_SECOND));
        if (session == null) {
            return null;
        }
        device.execute(() -> open(session, deviceName, 0));
        return new Capture(session, session.token);
    }

    private Session begin(
            boolean monitoring,
            ByteArrayOutputStream buffer,
            int maxSeconds,
            ChunkListener chunks,
            PcmListener pcm,
            PcmRingBuffer preRoll) {
        synchronized (lock) {
            if (closed || current != null) {
                return null;
            }
            long token = ++nextToken;
            Session session = new Session(
                    token,
                    monitoring,
                    buffer,
                    chunks,
                    pcm,
                    maxSeconds * BYTES_PER_SECOND,
                    preRoll == null ? new PcmRingBuffer(DEFAULT_PRE_ROLL_SECONDS * BYTES_PER_SECOND) : preRoll);
            current = session;
            return session;
        }
    }

    /** Stops the capture this token owns; the future is null when it was already superseded. */
    public CompletableFuture<byte[]> stop(Capture owner) {
        if (owner == null) {
            return null;
        }
        synchronized (lock) {
            if (current != owner.session) {
                return null;
            }
            current = null;
        }
        return end(owner.session);
    }

    /**
     * Ends the current capture. The device is closed on the capture thread; the returned future completes with the
     * clip once the reader has exited, so a caller on the client thread never waits for a device.
     */
    public CompletableFuture<byte[]> stop() {
        Session session;
        synchronized (lock) {
            session = current;
            current = null;
        }
        if (session == null) {
            return CompletableFuture.completedFuture(new byte[0]);
        }
        return end(session);
    }

    private CompletableFuture<byte[]> end(Session session) {
        session.stopped = true;
        session.level = 0f;
        device.execute(() -> close(session));
        return session.finished;
    }

    @Override
    public void close() {
        stop();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        device.shutdown();
    }

    /** A copy of the current capture's pre-roll; empty before any recording and never persisted. */
    public byte[] preRoll() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        return session == null ? new byte[0] : session.preRoll.snapshot();
    }

    /** True while a clip is being recorded for recognition; a monitoring session does not count. */
    public boolean isRecording() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        return session != null && !session.monitoring;
    }

    /** True while the microphone is open only to listen for the wake word. */
    public boolean isMonitoring() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        return session != null && session.monitoring;
    }

    /** The loudness of the current capture's last chunk, 0..1; what the HUD's bars follow. */
    public float level() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        return session == null ? 0f : session.level;
    }

    /** True while a clip capture is open, whether or not its device has finished opening. */
    public boolean hasPendingRecording() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        return session != null && !session.monitoring;
    }

    /** The failure a chunk listener raised during the last capture, or null. */
    public RuntimeException listenerFailure() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        RuntimeException failure = session == null ? null : session.listenerFailure;
        return failure != null ? failure : lastFailure;
    }

    private void open(Session session, String deviceName, int maxSeconds) {
        MicLine opened;
        try {
            opened = lines.open(deviceName);
        } catch (RuntimeException | LineUnavailableException failure) {
            forget(session);
            session.opened.completeExceptionally(failure);
            session.finished.complete(new byte[0]);
            return;
        }
        session.line = opened;
        if (session.stopped) {
            // Released while the device was opening: there is nothing to record and nothing to report.
            closeLine(opened);
            session.opened.complete(null);
            session.finished.complete(clip(session));
            return;
        }
        Thread reader = new Thread(() -> read(session), session.monitoring ? "friendai-mic-monitor" : "friendai-mic");
        reader.setDaemon(true);
        session.reader = reader;
        reader.start();
        session.opened.complete(null);
    }

    /** Runs on the capture thread: closes the line, keeps the tail chunk, and settles the clip. */
    private void close(Session session) {
        MicLine line = session.line;
        if (line != null) {
            closeLine(line);
            Thread reader = session.reader;
            if (reader != null && reader != Thread.currentThread()) {
                try {
                    reader.join(READER_JOIN_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        session.finished.complete(clip(session));
    }

    private void forget(Session session) {
        synchronized (lock) {
            if (current == session) {
                current = null;
            }
        }
    }

    /**
     * The one capture loop. It reads and publishes only its own session; a stop marks the session and closes the line
     * so the blocking read returns, and everything captured after that belongs to nobody.
     */
    private void read(Session session) {
        byte[] chunk = new byte[1024];
        int total = 0;
        try {
            while (!session.stopped) {
                MicLine line = session.line;
                if (line == null) {
                    break;
                }
                int n = line.read(chunk, 0, chunk.length);
                if (n <= 0 || session.stopped) {
                    break;
                }
                if (isCurrent(session)) {
                    session.level = AudioLevel.of(chunk, n);
                    session.preRoll.write(chunk, n);
                }
                if (session.buffer != null) {
                    synchronized (session.buffer) {
                        if (total >= session.limit) {
                            break;
                        }
                        int count = Math.min(n, session.limit - total);
                        session.buffer.write(chunk, 0, count);
                        total += count;
                    }
                }
                if (session.chunks != null) {
                    try {
                        session.chunks.onChunk(PcmConverter.toFloat(Arrays.copyOf(chunk, n)));
                    } catch (RuntimeException failure) {
                        // The recognizer refused the chunk (overflow or a closed session): stop capture and let the
                        // caller report the typed failure after release.
                        session.listenerFailure = failure;
                        lastFailure = failure;
                        session.stopped = true;
                        break;
                    }
                }
                if (session.pcm != null) {
                    try {
                        session.pcm.onPcm(chunk, n);
                    } catch (RuntimeException failure) {
                        session.listenerFailure = failure;
                        lastFailure = failure;
                        session.stopped = true;
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Closing a line during disconnect can interrupt a blocking read.
        } finally {
            session.finished.complete(clip(session));
        }
    }

    private boolean isCurrent(Session session) {
        synchronized (lock) {
            return current == session;
        }
    }

    private static byte[] clip(Session session) {
        if (session.monitoring || session.buffer == null) {
            return new byte[0];
        }
        synchronized (session.buffer) {
            return session.buffer.toByteArray();
        }
    }

    private static void closeLine(MicLine line) {
        try {
            line.stop();
        } catch (RuntimeException ignored) {
            // The audio provider may have stopped the line while disconnecting.
        }
        try {
            line.close();
        } catch (RuntimeException ignored) {
            // An already-closed line needs no second close.
        }
    }

    private static MicLine openRealLine(String deviceName) throws LineUnavailableException {
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
        TargetDataLine line = opened;
        return new MicLine() {
            @Override
            public int read(byte[] buffer, int offset, int length) {
                return line.read(buffer, offset, length);
            }

            @Override
            public void start() {
                line.start();
            }

            @Override
            public void stop() {
                line.stop();
            }

            @Override
            public void close() {
                line.close();
            }
        };
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
