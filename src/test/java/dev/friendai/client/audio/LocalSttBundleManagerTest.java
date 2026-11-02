package dev.friendai.client.audio;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.stt.LocalSttCatalog;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalModelFile;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.testutil.MockHttpServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSttBundleManagerTest {
    private static final byte[] MODEL_BYTES = "pretend this is an int8 onnx model".getBytes(UTF_8);
    private static final byte[] TOKENS_BYTES = "a\nb\nc\n".getBytes(UTF_8);
    private static final byte[] OTHER_BYTES = "second model file".getBytes(UTF_8);

    @TempDir
    Path dir;

    private MockHttpServer server;
    private LocalSttBundleManager manager;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        manager = new LocalSttBundleManager(dir, HttpClient.newHttpClient(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void installsEveryFileAndReportsEachOfThem() throws Exception {
        LocalSttModel model = twoFileModel();
        server.enqueue(200, MODEL_BYTES);
        server.enqueue(200, TOKENS_BYTES);
        List<String> started = new CopyOnWriteArrayList<>();
        List<Long> written = new CopyOnWriteArrayList<>();

        Path installed = manager.install(model, new LocalSttBundleManager.ProgressListener() {
                    @Override
                    public void fileStarted(LocalModelFile file) {
                        started.add(file.file());
                    }

                    @Override
                    public void bytesWritten(long bytes, long total) {
                        written.add(bytes);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertEquals(manager.dir(model), installed);
        assertEquals(
                Path.of("friendai", "models", "stt", "parakeet-test"),
                dir.relativize(installed),
                "the bundle is published under the catalog's relative path");
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(installed.resolve("model.int8.onnx")));
        assertArrayEquals(TOKENS_BYTES, Files.readAllBytes(installed.resolve("tokens.txt")));
        assertEquals(
                List.of("model.int8.onnx", "tokens.txt"), started, "each manifest file is reported once, in order");
        assertEquals(model.totalBytes(), lastByte(written), "progress ends at the aggregate size");
        assertTrue(manager.isInstalled(model));
        assertEquals(
                List.of("/parakeet-test/model.int8.onnx", "/parakeet-test/tokens.txt"),
                server.requests().stream()
                        .map(MockHttpServer.RecordedRequest::path)
                        .toList());
        assertNoStaging(model);
    }

    @Test
    void wrongSizeFailsAndLeavesNoInstall() throws Exception {
        LocalSttModel model = model("parakeet-test", file("model.int8.onnx", MODEL_BYTES));
        server.enqueue(200, "model.int8.onnx but short");

        ExecutionException e = assertThrows(
                ExecutionException.class, () -> manager.install(model).get(10, TimeUnit.SECONDS));

        assertEquals("friendai.voice.error.model_corrupt", userKey(e));
        assertNoInstall(model);
    }

    @Test
    void oversizedBodyFailsAndLeavesNoInstall() throws Exception {
        LocalSttModel model = model("parakeet-test", file("model.int8.onnx", MODEL_BYTES));
        byte[] longer = new byte[MODEL_BYTES.length + 1];
        System.arraycopy(MODEL_BYTES, 0, longer, 0, MODEL_BYTES.length);
        server.enqueue(200, longer);

        ExecutionException e = assertThrows(
                ExecutionException.class, () -> manager.install(model).get(10, TimeUnit.SECONDS));

        assertEquals("friendai.voice.error.model_corrupt", userKey(e));
        assertNoInstall(model);
    }

    @Test
    void wrongHashFailsAndLeavesNoInstall() throws Exception {
        byte[] sameLength = MODEL_BYTES.clone();
        sameLength[0] ^= 0x40;
        LocalSttModel model = model("parakeet-test", file("model.int8.onnx", MODEL_BYTES));
        server.enqueue(200, sameLength);

        ExecutionException e = assertThrows(
                ExecutionException.class, () -> manager.install(model).get(10, TimeUnit.SECONDS));

        assertEquals("friendai.voice.error.model_corrupt", userKey(e));
        assertNoInstall(model);
    }

    @Test
    void httpErrorOnALaterFileCleansStaging() throws Exception {
        LocalSttModel model = twoFileModel();
        server.enqueue(200, MODEL_BYTES);
        server.enqueue(500, "{}");

        ExecutionException e = assertThrows(
                ExecutionException.class, () -> manager.install(model).get(10, TimeUnit.SECONDS));

        assertEquals("friendai.error.llm.network", userKey(e));
        assertNoInstall(model);
    }

    @Test
    void alreadyInstalledBundleSkipsDownloadAndIsLeftUntouched() throws Exception {
        LocalSttModel model = model("parakeet-test", file("model.int8.onnx", MODEL_BYTES));
        Path bundle = manager.dir(model);
        Files.createDirectories(bundle);
        Files.write(bundle.resolve("model.int8.onnx"), MODEL_BYTES);
        assertTrue(manager.isInstalled(model));

        Path installed = manager.install(model).get(10, TimeUnit.SECONDS);

        assertEquals(bundle, installed);
        assertEquals(0, server.requests().size(), "a complete bundle is not downloaded again");
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(bundle.resolve("model.int8.onnx")));
    }

    @Test
    void failedRetryKeepsTheFilesOfThePreviousInstall() throws Exception {
        LocalSttModel model = twoFileModel();
        Path bundle = manager.dir(model);
        Files.createDirectories(bundle);
        Files.write(bundle.resolve("model.int8.onnx"), "an older model file".getBytes(UTF_8));
        assertFalse(manager.isInstalled(model), "the previous install is incomplete");

        server.enqueue(200, MODEL_BYTES);
        server.enqueue(503, "{}");
        assertThrows(ExecutionException.class, () -> manager.install(model).get(10, TimeUnit.SECONDS));

        assertEquals(
                "an older model file",
                Files.readString(bundle.resolve("model.int8.onnx")),
                "a failed retry publishes nothing over the old files");
        assertFalse(Files.exists(bundle.resolve("tokens.txt")));
        assertNoStaging(model);
    }

    @Test
    void failedInstallCanBeRetriedFromCleanStaging() throws Exception {
        LocalSttModel model = model("parakeet-test", file("model.int8.onnx", MODEL_BYTES));
        server.enqueue(500, "{}");
        assertThrows(ExecutionException.class, () -> manager.install(model).get(10, TimeUnit.SECONDS));
        assertNoInstall(model);

        server.enqueue(200, MODEL_BYTES);
        Path installed = manager.install(model).get(10, TimeUnit.SECONDS);

        assertEquals(manager.dir(model), installed);
        assertEquals(2, server.requests().size(), "the retry starts its own request");
        assertNoStaging(model);
    }

    @Test
    void leftoverStagingFromACrashIsDiscarded() throws Exception {
        LocalSttModel model = model("parakeet-test", file("model.int8.onnx", MODEL_BYTES));
        Path staging = manager.dir(model).getParent().resolve("parakeet-test.staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("model.int8.onnx.part"), "half written");
        server.enqueue(200, MODEL_BYTES);

        manager.install(model).get(10, TimeUnit.SECONDS);

        assertTrue(manager.isInstalled(model));
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(manager.dir(model).resolve("model.int8.onnx")));
        assertNoStaging(model);
    }

    @Test
    void cancellingOneCallerLeavesTheSharedInstallRunning() throws Exception {
        LocalSttModel model = twoFileModel();
        server.enqueueDelayed(200, MODEL_BYTES, 300);
        server.enqueue(200, TOKENS_BYTES);

        var first = manager.install(model);
        var second = manager.install(model);
        assertTrue(second.cancel(true));

        assertEquals(manager.dir(model), first.get(10, TimeUnit.SECONDS));
        assertTrue(manager.isInstalled(model));
        assertEquals(2, server.requests().size(), "both callers share one request per file");
        assertNoStaging(model);
    }

    @Test
    void installsIntoTheSharedLegacyDirectoryWithoutTouchingNeighbours() throws Exception {
        LocalSttModel legacy = new LocalSttModel(
                "tiny",
                "Whisper Tiny",
                LocalSttCatalog.ENGINE_WHISPER,
                "main",
                LocalSttCatalog.LEGACY_MODELS_DIR,
                List.of(file("ggml-tiny.bin", MODEL_BYTES)),
                List.of(LocalSttCatalog.MULTILINGUAL),
                false,
                "MIT");
        Path legacyDir = manager.dir(legacy);
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("ggml-base.bin"), "another model already installed");
        server.enqueue(200, MODEL_BYTES);

        manager.install(legacy).get(10, TimeUnit.SECONDS);

        assertTrue(manager.isInstalled(legacy));
        assertArrayEquals(MODEL_BYTES, Files.readAllBytes(legacyDir.resolve("ggml-tiny.bin")));
        assertEquals(
                "another model already installed",
                Files.readString(legacyDir.resolve("ggml-base.bin")),
                "a shared directory keeps the other models' files");
        assertNoStaging(legacy);
    }

    @Test
    void rejectsManifestsThatCannotDescribeABundle() {
        assertRejected(model("parakeet-test"));
        assertRejected(new LocalSttModel(
                "parakeet-test",
                "Parakeet",
                LocalSttCatalog.ENGINE_SHERPA,
                "rev",
                bundleDir("parakeet-test"),
                List.of(file("model.int8.onnx", MODEL_BYTES), file("model.int8.onnx", OTHER_BYTES)),
                List.of("multi"),
                false,
                "MIT"));
        assertRejected(model("parakeet-test", file("../escape.bin", MODEL_BYTES)));
        assertRejected(model("parakeet-test", file("nested/model.int8.onnx", MODEL_BYTES)));
        assertRejected(model("parakeet-test", new LocalModelFile("model.int8.onnx", 0, sha(MODEL_BYTES))));
        assertRejected(model("parakeet-test", new LocalModelFile("model.int8.onnx", MODEL_BYTES.length, "not-a-hash")));
        assertRejected(model("parakeet-test", manyFiles(LocalSttBundleManager.MAX_FILES + 1)));
        assertRejected(new LocalSttModel(
                "parakeet-test",
                "Parakeet",
                LocalSttCatalog.ENGINE_SHERPA,
                "rev",
                "../outside",
                List.of(file("model.int8.onnx", MODEL_BYTES)),
                List.of("multi"),
                false,
                "MIT"));
        assertEquals(0, server.requests().size(), "a broken manifest fails before any request");
    }

    private void assertRejected(LocalSttModel model) {
        assertThrows(IllegalArgumentException.class, () -> manager.install(model), "install rejects " + model);
        assertFalse(manager.isInstalled(model), "isInstalled reports " + model + " as not installed");
    }

    private static LocalModelFile[] manyFiles(int count) {
        LocalModelFile[] files = new LocalModelFile[count];
        for (int i = 0; i < count; i++) {
            files[i] = new LocalModelFile("file-" + i + ".bin", 1, sha(new byte[] {(byte) i}));
        }
        return files;
    }

    private static String bundleDir(String id) {
        return LocalSttCatalog.BUNDLE_MODELS_DIR + "/" + id;
    }

    private static long lastByte(List<Long> written) {
        return written.get(written.size() - 1);
    }

    private static LocalSttModel twoFileModel() {
        return model("parakeet-test", file("model.int8.onnx", MODEL_BYTES), file("tokens.txt", TOKENS_BYTES));
    }

    private static LocalSttModel model(String id, LocalModelFile... files) {
        return new LocalSttModel(
                id,
                "Parakeet",
                LocalSttCatalog.ENGINE_SHERPA,
                "rev",
                bundleDir(id),
                List.of(files),
                List.of("multi"),
                false,
                "MIT");
    }

    private static LocalModelFile file(String name, byte[] content) {
        return new LocalModelFile(name, content.length, sha(content));
    }

    private static String sha(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String userKey(ExecutionException e) {
        assertTrue(e.getCause() instanceof UserFacingException, "unexpected failure: " + e.getCause());
        return ((UserFacingException) e.getCause()).userMessage().key();
    }

    private void assertNoInstall(LocalSttModel model) throws IOException {
        assertFalse(Files.exists(manager.dir(model)), "a failed install publishes nothing");
        assertFalse(manager.isInstalled(model));
        assertNoStaging(model);
    }

    private void assertNoStaging(LocalSttModel model) throws IOException {
        Path parent = manager.dir(model).getParent();
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (var entries = Files.list(parent)) {
            List<String> leftovers = new ArrayList<>();
            entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> name.endsWith(".staging"))
                    .forEach(leftovers::add);
            assertEquals(List.of(), leftovers, "staging directories are cleaned up");
        }
    }
}
