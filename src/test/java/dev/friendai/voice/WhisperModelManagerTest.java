package dev.friendai.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.MockHttpServer;
import dev.friendai.text.UserFacingException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperModelManagerTest {
    private static final String CONTENT = "pretend this is a speech model";

    @TempDir
    Path dir;

    private MockHttpServer server;
    private WhisperModelManager manager;
    private ModelSpec spec;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockHttpServer();
        byte[] bytes = CONTENT.getBytes(StandardCharsets.UTF_8);
        String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        spec = new ModelSpec("ggml-test.bin", bytes.length, sha);
        manager = new WhisperModelManager(dir, HttpClient.newHttpClient(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void downloadsAndVerifies() throws Exception {
        server.enqueue(200, CONTENT);
        AtomicReference<Double> lastProgress = new AtomicReference<>(0.0);
        Path path = manager.download(spec, lastProgress::set).get();
        assertEquals(CONTENT, Files.readString(path));
        assertTrue(manager.isInstalled(spec));
        assertEquals(1.0, lastProgress.get());
        assertFalse(Files.exists(dir.resolve("ggml-test.bin.part")));
        assertEquals("/ggml-test.bin", server.requests().get(0).path());
    }

    @Test
    void corruptDownloadDeletedAndFails() {
        server.enqueue(200, "pretend this is a speech modeX");
        ExecutionException e = assertThrows(
                ExecutionException.class, () -> manager.download(spec, p -> {}).get());
        UserFacingException cause = (UserFacingException) e.getCause();
        assertEquals("friendai.voice.error.model_corrupt", cause.userMessage().key());
        assertFalse(Files.exists(dir.resolve("ggml-test.bin.part")));
        assertFalse(Files.exists(dir.resolve("ggml-test.bin")));
    }

    @Test
    void alreadyInstalledSkipsDownload() throws Exception {
        Files.writeString(dir.resolve("ggml-test.bin"), CONTENT);
        Path path = manager.download(spec, p -> {}).get();
        assertEquals(dir.resolve("ggml-test.bin"), path);
        assertEquals(0, server.requests().size());
    }

    @Test
    void httpErrorFailsWithoutLeavingFiles() {
        server.enqueue(404, "{}");
        assertThrows(
                ExecutionException.class, () -> manager.download(spec, p -> {}).get());
        assertFalse(Files.exists(dir.resolve("ggml-test.bin")));
    }

    @Test
    void builtInSpecsPointAtRealFiles() {
        assertEquals("ggml-base.bin", WhisperModel.BASE.spec().file());
        assertEquals(64, WhisperModel.TINY.spec().sha256().length());
        assertEquals(WhisperModel.BASE, WhisperModel.byId("nonsense"));
        assertEquals(WhisperModel.SMALL, WhisperModel.byId("SMALL"));
    }
}
