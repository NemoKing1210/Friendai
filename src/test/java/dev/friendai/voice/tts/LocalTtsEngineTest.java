package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.text.UserFacingException;
import dev.friendai.voice.WavCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class LocalTtsEngineTest {
    private static final VoiceModel MODEL =
            VoiceModels.byId("en_US-lessac-medium").orElseThrow();

    @Test
    void missingFilesAreNotReady(@TempDir Path dir) throws Exception {
        try (LocalTtsEngine engine = new LocalTtsEngine(MODEL, dir, 1.0, Runnable::run)) {
            assertFalse(engine.isReady());
            ExecutionException error = assertThrows(
                    ExecutionException.class, () -> engine.synthesize("Hello").get());
            assertEquals(
                    "friendai.voice.tts.error.no_voice",
                    ((UserFacingException) error.getCause()).userMessage().key());
        }
    }

    @Test
    void anInstalledVoiceIsReadyUntilClosed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(MODEL.modelFile()), "");
        Files.writeString(dir.resolve(MODEL.tokensFile()), "");
        Files.createDirectories(dir.resolve(MODEL.dataDir()));
        LocalTtsEngine engine = new LocalTtsEngine(MODEL, dir, 1.0, Runnable::run);
        assertTrue(engine.isReady());
        engine.close();
        assertFalse(engine.isReady());
        ExecutionException error = assertThrows(
                ExecutionException.class, () -> engine.synthesize("Hello").get());
        assertEquals(
                "friendai.voice.tts.error.failed",
                ((UserFacingException) error.getCause()).userMessage().key());
    }

    @EnabledIfSystemProperty(named = "friendai.tts.voice", matches = ".+")
    @Test
    void synthesizesWithAnInstalledVoice() throws IOException {
        Path dir = Path.of(System.getProperty("friendai.tts.voice"));
        try (LocalTtsEngine engine = new LocalTtsEngine(MODEL, dir, 1.0, Runnable::run)) {
            TtsClip clip = engine.synthesize("Hello from Friendai.").join();
            assertTrue(clip.pcm16le().length > 10_000);
            assertTrue(clip.sampleRate() > 0);
            Files.createDirectories(Path.of("build"));
            Files.write(Path.of("build/tts-smoke.wav"), WavCodec.encode(clip.pcm16le(), clip.sampleRate()));
        }
    }
}
