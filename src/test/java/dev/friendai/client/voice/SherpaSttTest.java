package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.stt.LocalSttCatalog;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalModelFile;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.features.speech.stt.LocalSttCatalog.SherpaFiles;
import dev.friendai.foundation.text.UserFacingException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SherpaSttTest {
    private static final LocalModelFile MODEL_FILE = new LocalModelFile("model.int8.onnx", 4, "a".repeat(64));

    @Test
    void transcribeDecodesOnAWorkerAndVerifiesOnlyOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger verifications = new AtomicInteger();
            AtomicReference<String> workerThread = new AtomicReference<>();
            AtomicInteger opened = new AtomicInteger();
            SherpaStt stt = new SherpaStt(
                    factory(
                            () -> {
                                opened.incrementAndGet();
                                workerThread.set(Thread.currentThread().getName());
                                return clip("hello");
                            },
                            () -> stream("", "unused")),
                    executor,
                    () -> {
                        verifications.incrementAndGet();
                        return true;
                    },
                    model(false, false));

            assertTrue(stt.isReady());
            assertEquals("hello", stt.transcribe(new float[] {0.1f}, "ru").get(5, TimeUnit.SECONDS));
            assertEquals("hello", stt.transcribe(new float[] {0.2f}, "ru").get(5, TimeUnit.SECONDS));

            assertEquals(1, verifications.get(), "the bundle is hashed once per engine");
            assertEquals(1, opened.get(), "the native model is opened once");
            assertNotEquals(Thread.currentThread().getName(), workerThread.get());
            assertTrue(workerThread.get().startsWith("pool-"), "a worker thread owns the native call");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anUnverifiedBundleFailsAsNoModelBeforeOpening() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger opened = new AtomicInteger();
            SherpaStt stt = new SherpaStt(
                    factory(
                            () -> {
                                opened.incrementAndGet();
                                return clip("hello");
                            },
                            () -> stream("", "unused")),
                    executor,
                    () -> false,
                    model(false, false));

            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> stt.transcribe(new float[] {0.1f}, "ru").get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof UserFacingException);
            assertEquals(
                    "friendai.voice.error.no_model",
                    ((UserFacingException) error.getCause()).userMessage().key());
            assertEquals(0, opened.get(), "a corrupt bundle never reaches the native factory");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeReleasesTheEngineAndExecutorExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger closed = new AtomicInteger();
        SherpaStt stt = new SherpaStt(
                factory(
                        () -> new SherpaStt.ClipEngine() {
                            @Override
                            public String transcribe(float[] samples16k) {
                                return "hello";
                            }

                            @Override
                            public void close() {
                                closed.incrementAndGet();
                            }
                        },
                        () -> stream("", "unused")),
                executor,
                () -> true,
                model(false, false));
        assertEquals("hello", stt.transcribe(new float[] {0.1f}, "ru").get(5, TimeUnit.SECONDS));

        stt.close();
        stt.close();

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, closed.get(), "the native clip engine is released once");
        assertFalse(stt.isReady());
    }

    @Test
    void onlyALanguagePromptModelReceivesTheLanguageTag() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> language = new AtomicReference<>("not called");
            SherpaStt prompting = new SherpaStt(
                    factory(() -> clip("x"), () -> stream("hello", "hello")), executor, () -> true, model(true, true));
            var session = prompting.startStreaming("RU-ru", null);
            session.feed(new float[] {0.1f});
            assertEquals("hello", session.finish().get(5, TimeUnit.SECONDS));
            language.set(prompting.effectiveLanguage("RU-ru"));
            assertEquals("ru-ru", language.get());

            SherpaStt detecting = new SherpaStt(
                    factory(() -> clip("x"), () -> stream("hello", "hello")),
                    executor,
                    () -> true,
                    model(false, false));
            assertNull(detecting.effectiveLanguage("RU-ru"), "an offline detector has no per-stream language");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aBlankLanguageBecomesAuto() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SherpaStt stt = new SherpaStt(
                    factory(() -> clip("x"), () -> stream("", "")), executor, () -> true, model(true, true));
            assertEquals("auto", stt.effectiveLanguage(""));
            assertEquals("auto", stt.effectiveLanguage(null));
            assertEquals("ja", stt.effectiveLanguage(" JA "));
        } finally {
            executor.shutdownNow();
        }
    }

    private static SherpaStt.ClipEngine clip(String text) {
        return new SherpaStt.ClipEngine() {
            @Override
            public String transcribe(float[] samples16k) {
                return text;
            }

            @Override
            public void close() {}
        };
    }

    private static SherpaStt.StreamEngine stream(String partial, String finish) {
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

    private static SherpaStt.EngineFactory factory(
            java.util.function.Supplier<SherpaStt.ClipEngine> clip,
            java.util.function.Supplier<SherpaStt.StreamEngine> stream) {
        return new SherpaStt.EngineFactory() {
            @Override
            public SherpaStt.ClipEngine openClip() {
                return clip.get();
            }

            @Override
            public SherpaStt.StreamEngine openStream(String language) {
                return stream.get();
            }
        };
    }

    private static LocalSttModel model(boolean streaming, boolean languagePrompt) {
        return new LocalSttModel(
                "test-model",
                "Test",
                LocalSttCatalog.ENGINE_SHERPA,
                "rev",
                "https://example.test",
                "friendai/models/stt/test-model",
                List.of(MODEL_FILE, new LocalModelFile("tokens.txt", 2, "b".repeat(64))),
                new SherpaFiles("model.int8.onnx", "model.int8.onnx", "model.int8.onnx", "tokens.txt"),
                List.of("en"),
                streaming,
                languagePrompt,
                LocalSttCatalog.MODEL_TYPE_NEMO_TRANSDUCER,
                100,
                "MIT",
                "https://example.test/license",
                "test");
    }
}
