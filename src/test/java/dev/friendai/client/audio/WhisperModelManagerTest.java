package dev.friendai.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.testutil.MockHttpServer;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
    void corruptDownloadDeletedAndFails() throws java.io.IOException {
        server.enqueue(200, "pretend this is a speech modeX");
        ExecutionException e = assertThrows(
                ExecutionException.class, () -> manager.download(spec, p -> {}).get());
        UserFacingException cause = (UserFacingException) e.getCause();
        assertEquals("friendai.voice.error.model_corrupt", cause.userMessage().key());
        assertNoStagingFiles();
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
    void twoCallsForTheSameModelShareOneDownload() throws Exception {
        server.enqueueDelayed(200, CONTENT.getBytes(StandardCharsets.UTF_8), 300);
        var first = manager.download(spec, progress -> {});
        var second = manager.download(spec, progress -> {});
        assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertTrue(manager.isInstalled(spec));
        assertEquals(1, server.requests().size(), "both callers share one HTTP request");
    }

    @Test
    void cancelingOneCallerLeavesTheSharedDownloadRunning() throws Exception {
        server.enqueueDelayed(200, CONTENT.getBytes(StandardCharsets.UTF_8), 300);
        var first = manager.download(spec, progress -> {});
        var second = manager.download(spec, progress -> {});
        assertTrue(second.cancel(true));
        assertEquals(dir.resolve("ggml-test.bin"), first.get(10, TimeUnit.SECONDS));
        assertTrue(manager.isInstalled(spec));
    }

    @Test
    void aFailedSharedDownloadCanBeRetried() throws Exception {
        server.enqueue(404, "{}");
        assertThrows(
                ExecutionException.class,
                () -> manager.download(spec, progress -> {}).get());
        server.enqueue(200, CONTENT);
        assertEquals(
                dir.resolve("ggml-test.bin"),
                manager.download(spec, progress -> {}).get());
        assertEquals(2, server.requests().size(), "the failed operation is forgotten before the retry");
    }

    @Test
    void differentModelsDownloadIndependently() throws Exception {
        byte[] other = "another model".getBytes(StandardCharsets.UTF_8);
        String otherSha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(other));
        ModelSpec otherSpec = new ModelSpec("ggml-other.bin", other.length, otherSha);
        server.enqueueDelayed(200, CONTENT.getBytes(StandardCharsets.UTF_8), 300);
        server.enqueue(200, other);
        var first = manager.download(spec, progress -> {});
        while (server.requests().isEmpty()) Thread.sleep(10);
        var second = manager.download(otherSpec, progress -> {});
        assertEquals(dir.resolve("ggml-other.bin"), second.get(10, TimeUnit.SECONDS));
        assertEquals(dir.resolve("ggml-test.bin"), first.get(10, TimeUnit.SECONDS));
    }

    @Test
    void oversizedBodyFailsWithoutLeavingStagingFiles() throws Exception {
        server.enqueue(200, CONTENT + "extra");
        ExecutionException e = assertThrows(
                ExecutionException.class,
                () -> manager.download(spec, progress -> {}).get());
        assertEquals(
                "friendai.voice.error.model_corrupt",
                ((UserFacingException) e.getCause()).userMessage().key());
        try (var files = Files.list(dir)) {
            assertTrue(
                    files.noneMatch(file -> file.getFileName().toString().contains(".part")),
                    "the failed operation cleans its own staging file");
        }
    }

    private void assertNoStagingFiles() throws java.io.IOException {
        try (var files = Files.list(dir)) {
            assertTrue(
                    files.noneMatch(file -> file.getFileName().toString().contains(".part")),
                    "the failed operation cleans its own staging file");
        }
    }

    @Test
    void builtInSpecsPointAtRealFiles() {
        assertEquals("ggml-base.bin", WhisperModel.BASE.spec().file());
        assertEquals(64, WhisperModel.TINY.spec().sha256().length());
        assertEquals(WhisperModel.BASE, WhisperModel.byId("nonsense"));
        assertEquals(WhisperModel.SMALL, WhisperModel.byId("SMALL"));
    }
}
