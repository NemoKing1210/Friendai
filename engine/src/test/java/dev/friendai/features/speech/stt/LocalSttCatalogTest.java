package dev.friendai.features.speech.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.stt.LocalSttCatalog.LocalModelFile;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalSttCatalogTest {
    @Test
    void catalogHasUniqueIds() {
        List<LocalSttModel> all = LocalSttCatalog.all();
        assertEquals(6, all.size());
        Set<String> ids = new HashSet<>();
        for (LocalSttModel model : all) {
            assertTrue(ids.add(model.id()), "duplicate id " + model.id());
        }
        assertEquals(
                List.of("tiny", "base", "small", "parakeet-v3", "nemotron-3.5", "gigaam-v3"),
                all.stream().map(LocalSttModel::id).toList());
    }

    // These literals are copied from src/client/java/dev/friendai/client/audio/WhisperModel.java: the pure
    // engine catalogue cannot import the client, so the values are duplicated here on purpose.
    @Test
    void legacyEntriesMatchWhisperModel() {
        assertLegacy(
                "tiny",
                "ggml-tiny.bin",
                77_691_713L,
                "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21");
        assertLegacy(
                "base",
                "ggml-base.bin",
                147_951_465L,
                "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe");
        assertLegacy(
                "small",
                "ggml-small.bin",
                487_601_967L,
                "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b");
    }

    private void assertLegacy(String id, String file, long size, String sha256) {
        LocalSttModel model = LocalSttCatalog.byId(id).orElseThrow();
        assertEquals(1, model.files().size(), id);
        LocalModelFile spec = model.files().get(0);
        assertEquals(file, spec.file(), id);
        assertEquals(size, spec.size(), id);
        assertEquals(sha256, spec.sha256(), id);
        assertEquals(LocalSttCatalog.LEGACY_MODELS_DIR, model.bundleDir(), id);
        assertEquals(LocalSttCatalog.ENGINE_WHISPER, model.engine(), id);
        assertEquals("main", model.revision(), id);
        assertEquals("MIT", model.license(), id);
        assertFalse(model.streaming(), id);
    }

    @Test
    void everyHashIsSixtyFourLowercaseHex() {
        for (LocalSttModel model : LocalSttCatalog.all()) {
            for (LocalModelFile file : model.files()) {
                assertTrue(
                        file.sha256().matches("[0-9a-f]{64}"),
                        model.id() + "/" + file.file() + " hash " + file.sha256());
            }
        }
    }

    @Test
    void newBundlesGetTheirOwnDirectories() {
        List<String> newDirs = LocalSttCatalog.all().stream()
                .filter(model -> model.engine().equals(LocalSttCatalog.ENGINE_SHERPA))
                .map(LocalSttModel::bundleDir)
                .toList();
        assertEquals(3, newDirs.size());
        assertEquals(3, new HashSet<>(newDirs).size(), "bundle directories must differ: " + newDirs);
        for (LocalSttModel model : LocalSttCatalog.all()) {
            if (model.engine().equals(LocalSttCatalog.ENGINE_SHERPA)) {
                assertEquals(LocalSttCatalog.BUNDLE_MODELS_DIR + "/" + model.id(), model.bundleDir());
                assertNotEquals(LocalSttCatalog.LEGACY_MODELS_DIR, model.bundleDir());
            }
        }
    }

    @Test
    void legacyFilesStayInTheSharedLegacyDirectory() {
        for (String id : List.of("tiny", "base", "small")) {
            assertEquals(
                    LocalSttCatalog.LEGACY_MODELS_DIR,
                    LocalSttCatalog.byId(id).orElseThrow().bundleDir(),
                    id);
        }
    }

    @Test
    void newEntriesAreSherpaBundles() {
        for (String id : List.of("parakeet-v3", "nemotron-3.5", "gigaam-v3")) {
            LocalSttModel model = LocalSttCatalog.byId(id).orElseThrow();
            assertEquals(LocalSttCatalog.ENGINE_SHERPA, model.engine(), id);
            assertFalse(model.displayName().isBlank(), id);
            assertFalse(model.revision().isBlank(), id);
            assertFalse(model.license().isBlank(), id);
            assertFalse(model.languages().isEmpty(), id);
            assertFalse(model.files().isEmpty(), id);
            assertTrue(model.files().stream().anyMatch(file -> file.file().endsWith(".onnx")), id);
            assertEquals("tokens.txt", model.files().get(1).file(), id);
        }
        assertEquals(
                List.of("ru"), LocalSttCatalog.byId("gigaam-v3").orElseThrow().languages());
        assertEquals(
                List.of(LocalSttCatalog.UNVERIFIED),
                LocalSttCatalog.byId("parakeet-v3").orElseThrow().languages());
        assertEquals(
                List.of(LocalSttCatalog.UNVERIFIED),
                LocalSttCatalog.byId("nemotron-3.5").orElseThrow().languages());
    }

    @Test
    void onlyNemotronStreams() {
        List<LocalSttModel> streaming =
                LocalSttCatalog.all().stream().filter(LocalSttModel::streaming).toList();
        assertEquals(1, streaming.size());
        assertEquals("nemotron-3.5", streaming.get(0).id());
    }

    @Test
    void unknownIdHasNoFallback() {
        assertTrue(LocalSttCatalog.byId("whisper-tiny").isEmpty());
        assertTrue(LocalSttCatalog.byId("ggml-tiny.bin").isEmpty());
        assertTrue(LocalSttCatalog.byId("").isEmpty());
        assertTrue(LocalSttCatalog.byId(null).isEmpty());
    }

    @Test
    void knownIdResolvesCaseInsensitivelyLikeTheLegacyCatalogue() {
        assertEquals("tiny", LocalSttCatalog.byId("TINY").orElseThrow().id());
        assertEquals(
                "gigaam-v3", LocalSttCatalog.byId("GigaAM-V3").orElseThrow().id());
    }

    @Test
    void filesAreSafeRelativePathsAndSizesAgree() {
        for (LocalSttModel model : LocalSttCatalog.all()) {
            long sum = 0;
            for (LocalModelFile file : model.files()) {
                assertFalse(file.file().isBlank(), model.id());
                assertFalse(file.file().contains("/"), model.id() + " " + file.file());
                assertFalse(file.file().contains("\\"), model.id() + " " + file.file());
                assertFalse(file.file().equals(".."), model.id());
                assertTrue(file.size() > 0, model.id() + " " + file.file());
                sum += file.size();
            }
            assertEquals(sum, model.totalBytes(), model.id());
        }
    }
}
