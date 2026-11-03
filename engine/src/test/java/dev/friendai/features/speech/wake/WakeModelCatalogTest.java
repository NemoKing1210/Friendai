package dev.friendai.features.speech.wake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.wake.WakeModelCatalog.WakeFile;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakeModel;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakePhrase;
import java.util.List;
import org.junit.jupiter.api.Test;

class WakeModelCatalogTest {
    private static WakeModel model() {
        return WakeModelCatalog.defaultModel();
    }

    @Test
    void theShippedBundleIsTheOneTheDetectorLoads() {
        WakeModel model = model();

        assertEquals("gigaspeech-3.3m", model.id());
        assertEquals(List.of(model), WakeModelCatalog.all());
        assertEquals(model, WakeModelCatalog.defaultModel());
        assertEquals(model, WakeModelCatalog.byId("GIGASPEECH-3.3M").orElseThrow());
        assertTrue(WakeModelCatalog.byId("unknown").isEmpty());
        assertTrue(WakeModelCatalog.byId(null).isEmpty());
        assertTrue(model.bundleDir().startsWith(WakeModelCatalog.BUNDLE_MODELS_DIR + "/"));
        assertEquals(WakeModelCatalog.BUNDLE_MODELS_DIR + "/gigaspeech-3.3m", model.bundleDir());
    }

    @Test
    void theManifestNamesAnImmutableRevisionAndExactBytes() {
        WakeModel model = model();

        assertEquals("pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01", model.repo());
        assertTrue(model.revision().matches("[0-9a-f]{40}"), model.revision());
        // The download base is the revision itself, not a branch: the files can never move under the installer.
        assertTrue(model.downloadBaseUrl().endsWith("/resolve/" + model.revision()), model.downloadBaseUrl());
        assertTrue(model.downloadBaseUrl().startsWith("https://modelscope.cn/models/" + model.repo() + "/"));

        long total = 0;
        for (WakeFile file : model.files()) {
            assertTrue(file.size() > 0, file.file());
            assertTrue(file.sha256().matches("[0-9a-f]{64}"), file.file());
            assertEquals(64, file.sha256().length(), file.file());
            total += file.size();
        }
        assertEquals(total, model.totalBytes());
        assertEquals(5, model.files().size());
        assertEquals(5_498_367L, model.totalBytes());
        assertTrue(model.files().stream().anyMatch(file -> file.file().equals("bpe.model")));
        assertTrue(model.files().stream().anyMatch(file -> file.file().equals("tokens.txt")));
        assertEquals("Apache-2.0", model.license());
        assertTrue(model.attribution().contains("GigaSpeech"));
    }

    @Test
    void theKeywordsFileIsThePhraseTokenLines() {
        WakeModel model = model();

        assertEquals(4, model.phrases().size());
        assertEquals("Hey friend", model.defaultPhrase().text());
        assertEquals("HEY FRIEND", model.defaultPhrase().keyword());

        StringBuilder expected = new StringBuilder();
        for (WakePhrase phrase : model.phrases()) {
            expected.append(phrase.tokens()).append('\n');
        }
        assertEquals(expected.toString(), model.keywordsText());
        assertTrue(model.keywordsText().startsWith("▁HE Y ▁FRIEND\n"));
        // Every token line is BPE units separated by single spaces, which is what the spotter parses.
        for (WakePhrase phrase : model.phrases()) {
            for (String unit : phrase.tokens().split(" ")) {
                assertFalse(unit.isEmpty(), phrase.tokens());
            }
        }
    }

    @Test
    void aPhraseIsMatchedIgnoringCaseAndSpacing() {
        WakeModel model = model();

        assertEquals(
                "Hello there", model.phrase("  hello   THERE ").orElseThrow().text());
        assertTrue(model.phrase("good morning").isEmpty());
        assertTrue(model.phrase(null).isEmpty());
        assertEquals(
                "HELLO THERE", WakeModelCatalog.keywordOf(model, "Hello There").orElseThrow());
        assertTrue(WakeModelCatalog.keywordOf(model, "good morning").isEmpty());
        assertTrue(WakeModelCatalog.keywordOf(null, "hey friend").isEmpty());
        assertEquals("", WakeModelCatalog.normalize(null));
        assertEquals("hey friend", WakeModelCatalog.normalize("  Hey   Friend "));
    }

    @Test
    void aBrokenManifestIsRejectedBeforeAnyRequest() {
        WakeModel model = model();
        List<WakeFile> files = model.files();

        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "bad-hash",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        model.downloadBaseUrl(),
                        List.of(new WakeFile(files.get(0).file(), 1L, "not-a-hash")),
                        model.phrases(),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "no-files",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        model.downloadBaseUrl(),
                        List.of(),
                        model.phrases(),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "mutable-revision",
                        model.displayName(),
                        model.repo(),
                        "main",
                        model.downloadBaseUrl(),
                        files,
                        model.phrases(),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "insecure",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        "http://example.invalid/models",
                        files,
                        model.phrases(),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "no-phrases",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        model.downloadBaseUrl(),
                        files,
                        List.of(),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "duplicate-phrase",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        model.downloadBaseUrl(),
                        files,
                        List.of(model.defaultPhrase(), new WakePhrase("hey   FRIEND", "▁HE Y ▁FRIEND")),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "escaping-dir",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        model.downloadBaseUrl(),
                        List.of(new WakeFile("../escape.onnx", 1L, "a".repeat(64))),
                        model.phrases(),
                        model.license(),
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(
                IllegalArgumentException.class,
                () -> WakeModelCatalog.validate(new WakeModel(
                        "no-licence",
                        model.displayName(),
                        model.repo(),
                        model.revision(),
                        model.downloadBaseUrl(),
                        files,
                        model.phrases(),
                        "",
                        model.licenseUrl(),
                        model.attribution(),
                        model.memoryMb())));
        assertThrows(IllegalArgumentException.class, () -> WakeModelCatalog.validate(null));
    }
}
