package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.text.UserFacingException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SherpaStreamingSessionTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void publishesChangingPartialsAndOneFinalTranscript() throws Exception {
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        SherpaStreamingSession session = new SherpaStreamingSession(
                executor,
                () -> new SherpaStt.StreamEngine() {
                    @Override
                    public void accept(float[] samples16k) {
                        accepted.incrementAndGet();
                    }

                    @Override
                    public String partial() {
                        return "word" + accepted.get();
                    }

                    @Override
                    public String finish() {
                        return " final text ";
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                },
                partials::add,
                null);

        session.feed(new float[] {0.1f});
        session.feed(new float[] {0.2f});
        String text = session.finish().get(5, TimeUnit.SECONDS);

        assertEquals("final text", text);
        assertEquals(2, accepted.get());
        assertTrue(partials.contains("word1"), "the first partial is published: " + partials);
        assertTrue(partials.contains("word2"), "a changed partial replaces the first: " + partials);
        assertTrue(closed.get(), "the engine is released once the session ends");
    }

    @Test
    void thePartialIsCappedByCodePoints() throws Exception {
        List<String> partials = new CopyOnWriteArrayList<>();
        String longText = "a".repeat(1_000);
        SherpaStreamingSession session =
                new SherpaStreamingSession(executor, () -> engine(longText, "done"), partials::add, null);

        session.feed(new float[] {0.1f});
        assertEquals("done", session.finish().get(5, TimeUnit.SECONDS));

        assertEquals(1, partials.size());
        assertEquals(
                SherpaStreamingSession.MAX_PARTIAL_CHARS + 1, partials.get(0).length());
    }

    @Test
    void aFullQueueIsATypedFailureInsteadOfDroppedAudio() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SherpaStreamingSession session = new SherpaStreamingSession(
                executor,
                () -> {
                    await(release);
                    return engine("", "done");
                },
                null,
                null);

        for (int i = 0; i < SherpaStreamingSession.MAX_QUEUED_CHUNKS; i++) {
            session.feed(new float[] {0.1f});
        }
        UserFacingException overflow = assertThrows(UserFacingException.class, () -> session.feed(new float[] {0.1f}));
        assertEquals("friendai.voice.error.overflow", overflow.userMessage().key());

        release.countDown();
        session.cancel();
    }

    @Test
    void cancelProducesNoTurnAndIsSafeTwice() throws Exception {
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        SherpaStreamingSession session = new SherpaStreamingSession(
                executor,
                () -> {
                    await(release);
                    return new SherpaStt.StreamEngine() {
                        @Override
                        public void accept(float[] samples16k) {}

                        @Override
                        public String partial() {
                            return "";
                        }

                        @Override
                        public String finish() {
                            finished.incrementAndGet();
                            return "must not be used";
                        }

                        @Override
                        public void close() {}
                    };
                },
                null,
                null);

        session.feed(new float[] {0.1f});
        var result = session.finish();
        session.cancel();
        session.cancel();
        release.countDown();

        assertEquals("", result.get(5, TimeUnit.SECONDS));
        assertEquals(0, finished.get());
        assertSame(result, session.finish(), "finish is idempotent");
        assertThrows(UserFacingException.class, () -> session.feed(new float[] {0.1f}));
    }

    @Test
    void aFailureToOpenTheModelIsReportedNotSilentlySwallowed() throws Exception {
        SherpaStreamingSession session = new SherpaStreamingSession(
                executor,
                () -> {
                    throw SherpaStt.noModel();
                },
                null,
                null);

        Throwable error = assertThrows(Exception.class, () -> session.finish().get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof UserFacingException);
        assertEquals(
                "friendai.voice.error.no_model",
                ((UserFacingException) error.getCause()).userMessage().key());
    }

    private static SherpaStt.StreamEngine engine(String partial, String finish) {
        return new SherpaStt.StreamEngine() {
            @Override
            public void accept(float[] samples16k) {}

            @Override
            public String partial() {
                return partial;
            }

            @Override
            public String finish() {
                return finish;
            }

            @Override
            public void close() {}
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
