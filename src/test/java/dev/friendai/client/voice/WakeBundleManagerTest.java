package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.wake.WakeModelCatalog;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakeFile;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakeModel;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakePhrase;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installer's verification half, without a network: a bundle only counts as installed when its marker, every
 * declared size and the keywords file agree, and a file whose bytes changed under a matching size is still refused.
 */
class WakeBundleManagerTest {
    private static final String REVISION = "d4664d22a51cefcb2232047e72a21cff36797c53";

    @TempDir
    Path gameDir;

    @Test
    void aCompleteBundleIsAdoptedWithoutARequest() throws IOException {
        WakeModel model = model();
        WakeBundleManager manager = new WakeBundleManager(gameDir, HttpClient.newHttpClient());

        assertFalse(manager.hasFiles(model), "nothing is installed yet");
        assertFalse(manager.verifyInstalled(model));

        writeBundle(model);

        assertTrue(manager.hasFiles(model));
        assertTrue(manager.verifyInstalled(model));
        assertEquals(manager.dir(model), manager.install(model).join(), "a verified bundle is adopted, not downloaded");
    }

    @Test
    void aTruncatedFileIsNotInstalled() throws IOException {
        WakeModel model = model();
        writeBundle(model);
        WakeBundleManager manager = new WakeBundleManager(gameDir, HttpClient.newHttpClient());
        assertTrue(manager.hasFiles(model));

        Path encoder = manager.dir(model).resolve(model.files().get(0).file());
        Files.write(encoder, new byte[] {1, 2, 3});

        assertFalse(manager.hasFiles(model), "size is part of the manifest");
        assertFalse(manager.verifyInstalled(model));
    }

    @Test
    void sameSizeWithDifferentBytesIsRefused() throws IOException {
        WakeModel model = model();
        writeBundle(model);
        WakeBundleManager manager = new WakeBundleManager(gameDir, HttpClient.newHttpClient());
        assertTrue(manager.verifyInstalled(model));

        WakeBundleManager fresh = new WakeBundleManager(gameDir, HttpClient.newHttpClient());
        Path tokens = fresh.dir(model).resolve("tokens.txt");
        Files.write(tokens, new byte[] {0});

        assertTrue(fresh.hasFiles(model), "the size still matches");
        assertFalse(fresh.verifyInstalled(model), "the hash does not");
    }

    @Test
    void aMissingMarkerLeavesTheBundleUninstalled() throws IOException {
        WakeModel model = model();
        writeBundle(model);
        WakeBundleManager manager = new WakeBundleManager(gameDir, HttpClient.newHttpClient());

        Files.deleteIfExists(manager.dir(model).resolve(markerName(model)));

        assertFalse(manager.hasFiles(model));
    }

    @Test
    void aDifferentKeywordsFileLeavesTheBundleUninstalled() throws IOException {
        WakeModel model = model();
        writeBundle(model);
        WakeBundleManager manager = new WakeBundleManager(gameDir, HttpClient.newHttpClient());

        Files.writeString(manager.dir(model).resolve(WakeModelCatalog.KEYWORDS_FILE), "▁OT HER\n");

        assertFalse(manager.hasFiles(model), "the spotter would match a phrase the settings cannot select");
    }

    private void writeBundle(WakeModel model) throws IOException {
        Path dir = gameDir.resolve(model.bundleDir());
        Files.createDirectories(dir);
        Files.write(dir.resolve("encoder-test.int8.onnx"), encoderBytes());
        Files.write(dir.resolve("tokens.txt"), tokensBytes());
        Files.writeString(dir.resolve(WakeModelCatalog.KEYWORDS_FILE), model.keywordsText());
        Files.writeString(dir.resolve(markerName(model)), model.revision() + "\n" + model.id());
    }

    private static String markerName(WakeModel model) {
        return ".friendai-wake-" + model.id() + ".txt";
    }

    /** One-byte files keep the test fast; the manifest still names real sizes, so the hashes are computed here. */
    private static WakeModel model() {
        return new WakeModel(
                "test-kws",
                "Test KWS",
                "owner/name",
                REVISION,
                "https://example.invalid/models/owner/name/resolve/" + REVISION,
                List.of(
                        new WakeFile("encoder-test.int8.onnx", 1L, sha256(encoderBytes())),
                        new WakeFile("tokens.txt", 1L, sha256(tokensBytes()))),
                List.of(new WakePhrase("Hey friend", "▁HE Y ▁FRIEND")),
                "Apache-2.0",
                "https://www.apache.org/licenses/LICENSE-2.0",
                "test bundle",
                1);
    }

    private static byte[] encoderBytes() {
        return new byte[] {(byte) 0xE8};
    }

    private static byte[] tokensBytes() {
        return new byte[] {5};
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
