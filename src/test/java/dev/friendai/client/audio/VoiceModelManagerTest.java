package dev.friendai.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.tts.VoiceModel;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.testutil.MockHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceModelManagerTest {
    @TempDir
    Path dir;

    private MockHttpServer server;
    private VoiceModelManager manager;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        manager = new VoiceModelManager(dir, HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void downloadsVerifiesAndExtractsARealPipersArchive() throws IOException {
        byte[] archive = tarBz2(files(
                "vits-piper-test-voice/", new byte[0],
                "vits-piper-test-voice/test-voice.onnx", new byte[] {1, 2},
                "vits-piper-test-voice/test-voice.onnx.json", new byte[] {5},
                "vits-piper-test-voice/MODEL_CARD", new byte[] {6},
                "vits-piper-test-voice/tokens.txt", new byte[] {3},
                "vits-piper-test-voice/espeak-ng-data/phontab", new byte[] {4}));
        server.enqueue(200, archive);
        VoiceModel model = model(archive);

        Path voiceDir = manager.download(model, progress -> {}).join();

        assertTrue(Files.isRegularFile(voiceDir.resolve("test-voice.onnx")));
        assertFalse(Files.exists(voiceDir.resolve("vits-piper-test-voice")));
        assertTrue(Files.isRegularFile(voiceDir.resolve("tokens.txt")));
        assertTrue(Files.isDirectory(voiceDir.resolve("espeak-ng-data")));
        assertFalse(Files.exists(dir.resolve(model.id() + ".tar.bz2.part")));
        assertFalse(Files.exists(dir.resolve(model.id() + ".tar.bz2")));
        assertTrue(manager.isInstalled(model));
    }

    @Test
    void hashMismatchCleansUp() throws IOException {
        byte[] archive = tarBz2(files("test-voice.onnx", new byte[] {1}));
        server.enqueue(200, archive);
        VoiceModel model = new VoiceModel(
                "test-voice",
                "Test",
                "en-US",
                server.baseUrl() + "/voice.tar.bz2",
                archive.length,
                "0".repeat(64),
                "MIT",
                "test-voice.onnx",
                "tokens.txt",
                "espeak-ng-data");

        ExecutionException e = assertThrows(
                ExecutionException.class,
                () -> manager.download(model, progress -> {}).get());

        assertEquals(
                "friendai.voice.tts.error.model_corrupt",
                ((UserFacingException) e.getCause()).userMessage().key());
        assertNoStagingFiles();
        assertFalse(Files.exists(manager.dir(model)));
    }

    @Test
    void traversalEntriesAreRejected() throws IOException {
        byte[] archive = tarBz2(files(
                "../escape.txt", new byte[] {1},
                "test-voice.onnx", new byte[] {2},
                "tokens.txt", new byte[] {3},
                "espeak-ng-data/phontab", new byte[] {4}));
        server.enqueue(200, archive);
        VoiceModel model = model(archive);

        ExecutionException e = assertThrows(
                ExecutionException.class,
                () -> manager.download(model, progress -> {}).get());

        assertEquals(
                "friendai.voice.tts.error.model_corrupt",
                ((UserFacingException) e.getCause()).userMessage().key());
        assertFalse(Files.exists(dir.resolve("escape.txt")));
        assertFalse(Files.exists(dir.getParent().resolve("escape.txt")));
        assertFalse(Files.exists(manager.dir(model)));
    }

    @Test
    void twoCallsForTheSameVoiceShareOneInstall() throws Exception {
        byte[] archive = tarBz2(files(
                "vits-piper-test-voice/", new byte[0],
                "vits-piper-test-voice/test-voice.onnx", new byte[] {1, 2},
                "vits-piper-test-voice/tokens.txt", new byte[] {3},
                "vits-piper-test-voice/espeak-ng-data/phontab", new byte[] {4}));
        server.enqueueDelayed(200, archive, 300);
        VoiceModel model = model(archive);
        var first = manager.download(model, progress -> {});
        var second = manager.download(model, progress -> {});
        assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertTrue(manager.isInstalled(model));
        assertEquals(1, server.requests().size(), "both callers share one HTTP request");
    }

    @Test
    void cancelingOneCallerLeavesTheSharedInstallRunning() throws Exception {
        byte[] archive = tarBz2(files(
                "vits-piper-test-voice/", new byte[0],
                "vits-piper-test-voice/test-voice.onnx", new byte[] {1, 2},
                "vits-piper-test-voice/tokens.txt", new byte[] {3},
                "vits-piper-test-voice/espeak-ng-data/phontab", new byte[] {4}));
        server.enqueueDelayed(200, archive, 300);
        VoiceModel model = model(archive);
        var first = manager.download(model, progress -> {});
        var second = manager.download(model, progress -> {});
        assertTrue(second.cancel(true));
        assertEquals(manager.dir(model), first.get(10, TimeUnit.SECONDS));
        assertTrue(manager.isInstalled(model));
    }

    @Test
    void aFailedInstallCanBeRetried() throws Exception {
        byte[] archive = tarBz2(files(
                "vits-piper-test-voice/", new byte[0],
                "vits-piper-test-voice/test-voice.onnx", new byte[] {1, 2},
                "vits-piper-test-voice/tokens.txt", new byte[] {3},
                "vits-piper-test-voice/espeak-ng-data/phontab", new byte[] {4}));
        VoiceModel model = model(archive);
        server.enqueue(404, "{}");
        assertThrows(
                ExecutionException.class,
                () -> manager.download(model, progress -> {}).get());
        server.enqueue(200, archive);
        assertEquals(manager.dir(model), manager.download(model, progress -> {}).get());
        assertEquals(2, server.requests().size(), "the failed operation is forgotten before the retry");
    }

    private void assertNoStagingFiles() throws java.io.IOException {
        try (var files = Files.list(dir)) {
            assertTrue(
                    files.noneMatch(file -> file.getFileName().toString().contains(".part")),
                    "the failed operation cleans its own staging paths");
        }
    }

    @Test
    void installedVoiceIsNotDownloadedAgain() throws IOException {
        byte[] archive = tarBz2(files(
                "vits-piper-test-voice/", new byte[0],
                "vits-piper-test-voice/test-voice.onnx", new byte[] {1},
                "vits-piper-test-voice/tokens.txt", new byte[] {2},
                "vits-piper-test-voice/espeak-ng-data/phontab", new byte[] {3}));
        server.enqueue(200, archive);
        VoiceModel model = model(archive);

        manager.download(model, progress -> {}).join();
        assertEquals(1, server.requests().size());
        assertEquals(manager.dir(model), manager.download(model, progress -> {}).join());
        assertEquals(1, server.requests().size());
    }

    @Test
    void reportsInstallProgressAfterTheDownloadFinishes() throws IOException {
        byte[] archive = tarBz2(files(
                "vits-piper-test-voice/", new byte[0],
                "vits-piper-test-voice/test-voice.onnx", new byte[4096],
                "vits-piper-test-voice/tokens.txt", new byte[] {2},
                "vits-piper-test-voice/espeak-ng-data/phontab", new byte[] {3}));
        server.enqueue(200, archive);
        VoiceModel model = model(archive);
        List<String> events = new CopyOnWriteArrayList<>();

        manager.download(model, new VoiceModelManager.DownloadStage() {
                    @Override
                    public void downloading(double fraction) {
                        if (fraction >= 1.0) {
                            events.add("downloaded");
                        }
                    }

                    @Override
                    public void installing(double fraction) {
                        events.add("installing " + fraction);
                    }
                })
                .join();

        assertEquals("downloaded", events.get(0), events.toString());
        assertTrue(events.size() > 2, "expected several install reports, got " + events);
        assertEquals("installing 1.0", events.get(events.size() - 1), events.toString());
        assertTrue(
                events.subList(1, events.size()).stream().allMatch(e -> e.startsWith("installing")), events.toString());
    }

    private VoiceModel model(byte[] archive) {
        return new VoiceModel(
                "test-voice",
                "Test",
                "en-US",
                server.baseUrl() + "/voice.tar.bz2",
                archive.length,
                sha256(archive),
                "MIT",
                "test-voice.onnx",
                "tokens.txt",
                "espeak-ng-data");
    }

    private static Map<String, byte[]> files(Object... namesAndBodies) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < namesAndBodies.length; i += 2) {
            files.put((String) namesAndBodies[i], (byte[]) namesAndBodies[i + 1]);
        }
        return files;
    }

    private static byte[] tarBz2(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new BZip2CompressorOutputStream(bytes))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                if (!file.getKey().endsWith("/")) {
                    entry.setSize(file.getValue().length);
                }
                tar.putArchiveEntry(entry);
                if (!file.getKey().endsWith("/")) {
                    tar.write(file.getValue());
                }
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
