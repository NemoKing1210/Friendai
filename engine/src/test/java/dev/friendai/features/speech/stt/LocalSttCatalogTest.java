package dev.friendai.features.speech.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.stt.LocalSttCatalog.LocalModelFile;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.features.speech.stt.LocalSttCatalog.SherpaFiles;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalSttCatalogTest {
    @Test
    void catalogHasUniqueIdsInStableOrder() {
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

    @Test
    void legacyEntriesKeepTheirExistingFilesAndPaths() {
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
        assertNotNull(model.downloadBaseUrl(), id);
        assertTrue(model.downloadBaseUrl().endsWith("/resolve/main"), id);
        assertFalse(model.streaming(), id);
        assertFalse(model.languagePrompt(), id);
        assertEquals(null, model.sherpa(), id);
        assertTrue(model.supportsLanguage("auto"), id);
        assertTrue(model.supportsLanguage("de"), "whisper is multilingual: " + id);
    }

    @Test
    void sherpaBundlesCarryVerifiedRevisionsSizesHashesAndLicences() {
        assertSherpa(
                "parakeet-v3",
                "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
                "2bda32ec70b097a55adaa07d9a7173915b43cc78",
                "CC-BY-4.0",
                false,
                false,
                List.of(
                        file(
                                "decoder.int8.onnx",
                                11_845_275L,
                                "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                        file(
                                "encoder.int8.onnx",
                                652_184_281L,
                                "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                        file(
                                "joiner.int8.onnx",
                                6_355_277L,
                                "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                        file(
                                "tokens.txt",
                                93_939L,
                                "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")));
        assertSherpa(
                "nemotron-3.5",
                "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
                "ab43d895f5985b1bbab8b6eac8607fcdc05343f3",
                "OpenMDW-1.1",
                true,
                true,
                List.of(
                        file(
                                "decoder.int8.onnx",
                                14_978_075L,
                                "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0"),
                        file(
                                "encoder.int8.onnx",
                                657_601_403L,
                                "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68"),
                        file(
                                "joiner.int8.onnx",
                                9_504_438L,
                                "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce"),
                        file(
                                "tokens.txt",
                                131_440L,
                                "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a")));
        assertSherpa(
                "gigaam-v3",
                "csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16",
                "a6039be7cee829a9044a69ac0ebaf1c191217c97",
                "MIT",
                false,
                false,
                List.of(
                        file(
                                "decoder.onnx",
                                4_600_132L,
                                "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
                        file(
                                "encoder.int8.onnx",
                                224_570_820L,
                                "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
                        file(
                                "joiner.onnx",
                                2_712_896L,
                                "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
                        file(
                                "tokens.txt",
                                13_354L,
                                "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7")));
    }

    private void assertSherpa(
            String id,
            String repo,
            String revision,
            String license,
            boolean streaming,
            boolean languagePrompt,
            List<LocalModelFile> files) {
        LocalSttModel model = LocalSttCatalog.byId(id).orElseThrow();
        assertEquals(LocalSttCatalog.ENGINE_SHERPA, model.engine(), id);
        assertEquals(LocalSttCatalog.MODEL_TYPE_NEMO_TRANSDUCER, model.modelType(), id);
        assertEquals(revision, model.revision(), id);
        assertEquals("https://huggingface.co/" + repo + "/resolve/" + revision, model.downloadBaseUrl(), id);
        assertEquals(LocalSttCatalog.BUNDLE_MODELS_DIR + "/" + id, model.bundleDir(), id);
        assertEquals(files, model.files(), id);
        assertEquals(streaming, model.streaming(), id);
        assertEquals(languagePrompt, model.languagePrompt(), id);
        assertEquals(license, model.license(), id);
        assertFalse(model.licenseUrl().isBlank(), id);
        assertFalse(model.attribution().isBlank(), id);
        assertTrue(model.memoryMb() > 0, id);
        assertNotNull(model.sherpa(), id);
        Set<String> names =
                new HashSet<>(files.stream().map(LocalModelFile::file).toList());
        assertTrue(names.contains(model.sherpa().encoder()), id);
        assertTrue(names.contains(model.sherpa().decoder()), id);
        assertTrue(names.contains(model.sherpa().joiner()), id);
        assertTrue(names.contains(model.sherpa().tokens()), id);
    }

    @Test
    void onlyNemotronStreamsAndTakesALanguagePrompt() {
        List<LocalSttModel> streaming =
                LocalSttCatalog.all().stream().filter(LocalSttModel::streaming).toList();
        assertEquals(1, streaming.size());
        assertEquals("nemotron-3.5", streaming.get(0).id());
        assertEquals(
                List.of("nemotron-3.5"),
                LocalSttCatalog.all().stream()
                        .filter(LocalSttModel::languagePrompt)
                        .map(LocalSttModel::id)
                        .toList());
    }

    @Test
    void languageSupportComesFromModelEvidence() {
        LocalSttModel gigaam = LocalSttCatalog.byId("gigaam-v3").orElseThrow();
        assertTrue(gigaam.supportsLanguage("ru"));
        assertTrue(gigaam.supportsLanguage("ru-RU"));
        assertTrue(gigaam.supportsLanguage("auto"));
        assertTrue(gigaam.supportsLanguage(""));
        assertTrue(gigaam.supportsLanguage(null));
        assertFalse(gigaam.supportsLanguage("en"));

        LocalSttModel parakeet = LocalSttCatalog.byId("parakeet-v3").orElseThrow();
        assertTrue(parakeet.supportsLanguage("ru"));
        assertTrue(parakeet.supportsLanguage("uk"));
        assertTrue(parakeet.supportsLanguage("de-DE"));
        assertFalse(parakeet.supportsLanguage("ja"));

        LocalSttModel nemotron = LocalSttCatalog.byId("nemotron-3.5").orElseThrow();
        assertTrue(nemotron.supportsLanguage("ja"));
        assertTrue(nemotron.supportsLanguage("ru-RU"));
        assertFalse(nemotron.supportsLanguage("th"));
    }

    @Test
    void everyHashIsSixtyFourLowercaseHexAndNoPlaceholderRemains() {
        for (LocalSttModel model : LocalSttCatalog.all()) {
            for (LocalModelFile file : model.files()) {
                assertTrue(
                        file.sha256().matches("[0-9a-f]{64}"),
                        model.id() + "/" + file.file() + " hash " + file.sha256());
                assertTrue(file.size() > 1, "placeholder size in " + model.id());
                assertFalse(file.sha256().startsWith("0000"), "placeholder hash in " + model.id());
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
    }

    @Test
    void validateRejectsUnsupportedManifests() {
        assertRejected(model("bad id", List.of(file("model.onnx", 4, hash('a')))));
        assertRejected(model("ok", List.of(file("../escape.onnx", 4, hash('a')))));
        assertRejected(model("ok", List.of(file("model.onnx", 0, hash('a')))));
        assertRejected(model("ok", List.of(file("model.onnx", 4, "not-a-hash"))));
        assertRejected(model("ok", List.of(file("model.onnx", 4, hash('A')))));
        assertRejected(model("ok", List.of(file("model.onnx", 4, hash('a')), file("model.onnx", 4, hash('b')))));
        assertRejected(model("ok", List.of()));

        LocalSttModel whisper = new LocalSttModel(
                "legacy",
                "Legacy",
                LocalSttCatalog.ENGINE_WHISPER,
                "main",
                "https://example.test",
                LocalSttCatalog.LEGACY_MODELS_DIR,
                List.of(file("m.bin", 4, hash('a'))),
                null,
                List.of(LocalSttCatalog.MULTILINGUAL),
                false,
                false,
                "whisper.cpp",
                100,
                "MIT",
                "https://example.test/l",
                "attribution");
        LocalSttCatalog.validate(whisper);

        LocalSttModel unknownEngine = new LocalSttModel(
                "x",
                "X",
                "other",
                "rev",
                "https://example.test",
                "friendai/models/stt/x",
                List.of(file("m.onnx", 4, hash('a'))),
                null,
                List.of("en"),
                false,
                false,
                LocalSttCatalog.MODEL_TYPE_NEMO_TRANSDUCER,
                100,
                "MIT",
                "https://example.test/l",
                "attribution");
        assertThrows(IllegalArgumentException.class, () -> LocalSttCatalog.validate(unknownEngine));

        LocalSttModel streamingWithoutPrompt = new LocalSttModel(
                "x",
                "X",
                LocalSttCatalog.ENGINE_SHERPA,
                "rev",
                "https://example.test",
                "friendai/models/stt/x",
                List.of(file("m.onnx", 4, hash('a'))),
                new SherpaFiles("m.onnx", "m.onnx", "m.onnx", "m.onnx"),
                List.of("en"),
                true,
                false,
                LocalSttCatalog.MODEL_TYPE_NEMO_TRANSDUCER,
                100,
                "MIT",
                "https://example.test/l",
                "attribution");
        assertThrows(IllegalArgumentException.class, () -> LocalSttCatalog.validate(streamingWithoutPrompt));
    }

    private static void assertRejected(LocalSttModel model) {
        assertThrows(IllegalArgumentException.class, () -> LocalSttCatalog.validate(model), model.toString());
    }

    private static LocalSttModel model(String id, List<LocalModelFile> files) {
        return new LocalSttModel(
                id,
                "Test",
                LocalSttCatalog.ENGINE_SHERPA,
                "rev",
                "https://example.test",
                LocalSttCatalog.BUNDLE_MODELS_DIR + "/x",
                files,
                new SherpaFiles("model.onnx", "model.onnx", "model.onnx", "model.onnx"),
                List.of("en"),
                false,
                false,
                LocalSttCatalog.MODEL_TYPE_NEMO_TRANSDUCER,
                100,
                "MIT",
                "https://example.test/l",
                "attribution");
    }

    private static LocalModelFile file(String name, long size, String sha256) {
        return new LocalModelFile(name, size, sha256);
    }

    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    void unknownIdHasNoFallback() {
        assertTrue(LocalSttCatalog.byId("whisper-tiny").isEmpty());
        assertTrue(LocalSttCatalog.byId("ggml-tiny.bin").isEmpty());
        assertTrue(LocalSttCatalog.byId("").isEmpty());
        assertTrue(LocalSttCatalog.byId(null).isEmpty());
    }

    @Test
    void knownIdResolvesCaseInsensitively() {
        assertEquals("tiny", LocalSttCatalog.byId("TINY").orElseThrow().id());
        assertEquals(
                "gigaam-v3", LocalSttCatalog.byId("GigaAM-V3").orElseThrow().id());
    }

    @Test
    void fileNamesAreOneSafeSegmentAndSizesAddUp() {
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
