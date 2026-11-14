package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.LineUnavailableException;
import org.junit.jupiter.api.Test;

/** The capture lifecycle, driven through the line seam so no audio device is needed. */
class MicCaptureTest {
    private static final byte[] CHUNK = pattern(1_024);

    @Test
    void aStopDoesNotBlockTheCallerAndStillReturnsTheClip() throws Exception {
        FakeLine line = new FakeLine();
        try (MicCapture mic = new MicCapture(name -> line)) {
            MicCapture.Capture capture = mic.start("device", 5);
            assertNotNull(capture);
            capture.opened().get(2, TimeUnit.SECONDS);
            line.offer(CHUNK);
            await(() -> mic.level() > 0f);
            // This device holds its blocking read until the test lets it go, so only a stop that waits for the
            // reader would take any time at all.
            line.releaseAfterStop = true;

            long started = System.nanoTime();
            CompletableFuture<byte[]> stopping = mic.stop(capture);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 100, "the caller waited " + elapsedMillis + " ms for the device");
            assertNotNull(stopping);
            line.release();
            assertArrayEquals(CHUNK, stopping.get(2, TimeUnit.SECONDS), "the clip holds what was captured");
        }
    }

    @Test
    void aReadThatReturnsAfterStopCannotReachTheNextCapture() throws Exception {
        LineSource lines = new LineSource();
        FakeLine first = new FakeLine();
        FakeLine second = new FakeLine();
        lines.next = first;
        try (MicCapture mic = new MicCapture(lines)) {
            MicCapture.Capture one = mic.start("device", 5);
            assertNotNull(one);
            one.opened().get(2, TimeUnit.SECONDS);

            first.releaseAfterStop = true;
            CompletableFuture<byte[]> firstClip = mic.stop(one);
            // The old device hands one more chunk back only now, after the capture was told to stop.
            first.release();

            lines.next = second;
            MicCapture.Capture two = mic.start("device", 5);
            assertNotNull(two);
            two.opened().get(2, TimeUnit.SECONDS);
            second.offer(CHUNK);
            await(() -> mic.level() > 0f);

            assertArrayEquals(CHUNK, mic.stop(two).get(2, TimeUnit.SECONDS), "only the second capture's audio");
            assertEquals(0, firstClip.get(2, TimeUnit.SECONDS).length, "the late chunk belongs to nobody");
        }
    }

    @Test
    void aDeviceThatRefusesToOpenReportsItWithoutBlocking() throws Exception {
        try (MicCapture mic = new MicCapture(name -> {
            throw new LineUnavailableException("no device");
        })) {
            MicCapture.Capture capture = mic.start("device", 5);
            assertNotNull(capture);
            try {
                capture.opened().get(2, TimeUnit.SECONDS);
                fail("the open failure must reach the caller");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof LineUnavailableException);
            }
            assertFalse(mic.isRecording(), "a device that never opened owns nothing");
            assertNull(mic.stop(capture), "the failed capture is no longer the current one");
            assertEquals(0, mic.stop().get(2, TimeUnit.SECONDS).length);
        }
    }

    @Test
    void aSecondStartWhileOneCaptureIsOpenIsRefused() throws Exception {
        FakeLine line = new FakeLine();
        try (MicCapture mic = new MicCapture(name -> line)) {
            MicCapture.Capture first = mic.start("device", 5);
            assertNotNull(first);
            assertNull(mic.start("device", 5), "a second start while one capture is open is refused");
            assertNull(mic.startMonitoring("device", 2, (pcm, length) -> {}));
            assertNotNull(mic.stop(first));
        }
    }

    @Test
    void anOlderTokenCannotStopANewerCapture() throws Exception {
        LineSource lines = new LineSource();
        FakeLine firstLine = new FakeLine();
        FakeLine secondLine = new FakeLine();
        lines.next = firstLine;
        try (MicCapture mic = new MicCapture(lines)) {
            MicCapture.Capture first = mic.start("device", 5);
            first.opened().get(2, TimeUnit.SECONDS);
            mic.stop(first);

            lines.next = secondLine;
            MicCapture.Capture second = mic.start("device", 5);
            second.opened().get(2, TimeUnit.SECONDS);
            assertNull(mic.stop(first), "a token from the previous capture stops nothing");
            assertTrue(mic.isRecording(), "the newer capture still owns the device");
            secondLine.offer(CHUNK);
            await(() -> mic.level() > 0f);
            assertArrayEquals(CHUNK, mic.stop(second).get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void monitoringPublishesLevelsButKeepsNoClip() throws Exception {
        FakeLine line = new FakeLine();
        try (MicCapture mic = new MicCapture(name -> line)) {
            MicCapture.Capture capture = mic.startMonitoring("device", 2, (pcm, length) -> {});
            assertNotNull(capture);
            capture.opened().get(2, TimeUnit.SECONDS);
            assertTrue(mic.isMonitoring());
            assertFalse(mic.isRecording());
            line.offer(CHUNK);
            await(() -> mic.level() > 0f);
            assertEquals(0, mic.stop(capture).get(2, TimeUnit.SECONDS).length);
            assertEquals(0f, mic.level(), "a stopped capture publishes no level");
        }
    }

    @Test
    void closingEndsTheActiveCaptureWithoutBlocking() throws Exception {
        FakeLine line = new FakeLine();
        MicCapture mic = new MicCapture(name -> line);
        MicCapture.Capture capture = mic.start("device", 5);
        assertNotNull(capture);
        capture.opened().get(2, TimeUnit.SECONDS);
        line.offer(CHUNK);
        await(() -> mic.level() > 0f);
        // A disconnect reaches this on the client thread: the device close must happen on the capture thread.
        line.releaseAfterStop = true;

        long started = System.nanoTime();
        mic.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 100, "closing waited " + elapsedMillis + " ms for the device");
        assertFalse(mic.isRecording(), "a closed capture owns nothing");
        assertNull(mic.stop(capture), "the token of a closed capture stops nothing");
        await(line::isClosed);
    }

    @Test
    void aClosedCaptureRefusesToStartAgain() {
        MicCapture mic = new MicCapture(name -> new FakeLine());
        mic.close();
        assertNull(mic.start("device", 5));
        assertNull(mic.startMonitoring("device", 2, (pcm, length) -> {}));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("the condition did not become true");
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    /** Hands out lines in order, so the second capture gets a different scripted device. */
    private static final class LineSource implements MicCapture.LineFactory {
        private MicCapture.MicLine next;

        @Override
        public MicCapture.MicLine open(String deviceName) {
            return next;
        }
    }

    /** A device that blocks in read until a chunk arrives or it is closed, and can hold a read past a stop. */
    private static final class FakeLine implements MicCapture.MicLine {
        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean releaseAfterStop;
        private boolean lateDelivered;

        void offer(byte[] chunk) {
            chunks.offer(chunk);
        }

        /** Lets one read that was already blocked return a chunk after the stop. */
        void release() {
            released.countDown();
        }

        boolean isClosed() {
            return closed.getCount() == 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            try {
                while (true) {
                    byte[] chunk = chunks.poll(20, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        int count = Math.min(length, chunk.length);
                        System.arraycopy(chunk, 0, buffer, offset, count);
                        return count;
                    }
                    if (closed.getCount() == 0) {
                        if (releaseAfterStop) {
                            // A device that completes a blocking read only after it was closed.
                            released.await(2, TimeUnit.SECONDS);
                            if (!lateDelivered) {
                                lateDelivered = true;
                                int count = Math.min(length, CHUNK.length);
                                System.arraycopy(CHUNK, 0, buffer, offset, count);
                                return count;
                            }
                        }
                        return -1;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public void start() {
            // nothing to start
        }

        @Override
        public void stop() {
            // the close is what ends a blocking read here
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
