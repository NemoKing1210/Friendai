package dev.friendai.features.speech.wake;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The local wake-word bundles this mod can install and run.
 *
 * <p>One entry: the sherpa-onnx GigaSpeech keyword-spotting zipformer. Its model files come from the upstream
 * ModelScope repository pinned to an immutable revision; every size and SHA-256 below was taken from those exact
 * bytes and cross-checked against the upstream release archive (its own checksum file). A value here is a verified
 * fact, not an estimate.
 *
 * <p>The spotter takes keywords already split into the bundle's own BPE units, so the phrases an owner can pick are
 * listed here with their token lines; {@link WakeModel#keywordsText()} is the {@code keywords.txt} the installer
 * writes into the bundle. Only the phrases in this list can be detected by the shipped bundle — the token table is
 * tiny (500 entries) and building tokens for arbitrary text would need a sentencepiece encoder in the mod.
 *
 * <p>Adding a model means one entry: nothing else in the wake pipeline names a model.
 */
public final class WakeModelCatalog {
    /** Directory every wake bundle installs into, under the game directory. */
    public static final String BUNDLE_MODELS_DIR = "friendai/models/wake";

    /** File the installer writes from {@link WakeModel#keywordsText()}; the spotter opens it at load. */
    public static final String KEYWORDS_FILE = "keywords.txt";

    /** The sherpa-onnx engine family; the wake detector is the keyword spotter of the same build. */
    public static final String ENGINE_SHERPA = "sherpa-onnx";

    /** Sample rate every bundle here expects, matching {@code MicCapture}. */
    public static final int SAMPLE_RATE = 16_000;

    private static final String GIGASPEECH_REPO = "pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01";

    /** Commit of the upstream ModelScope repository; the download base resolves per file against it. */
    private static final String GIGASPEECH_REVISION = "d4664d22a51cefcb2232047e72a21cff36797c53";

    private static final List<WakePhrase> GIGASPEECH_PHRASES = List.of(
            // Tokens from the bundle's own BPE model: the model was trained on upper-case text, and "▁" marks the
            // start of a word. These lines are what the spotter matches, so they are data, not derived text.
            new WakePhrase("Hey friend", "▁HE Y ▁FRIEND"),
            new WakePhrase("Hello friend", "▁HE LL O ▁FRIEND"),
            new WakePhrase("Hey buddy", "▁HE Y ▁BU D D Y"),
            new WakePhrase("Hello there", "▁HE LL O ▁THERE"));

    private static final List<WakeModel> ALL = List.of(new WakeModel(
            "gigaspeech-3.3m",
            "GigaSpeech KWS 3.3M",
            GIGASPEECH_REPO,
            GIGASPEECH_REVISION,
            "https://modelscope.cn/models/" + GIGASPEECH_REPO + "/resolve/" + GIGASPEECH_REVISION,
            List.of(
                    file(
                            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            4_807_159L,
                            "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"),
                    file(
                            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            277_985L,
                            "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a"),
                    file(
                            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            163_380L,
                            "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"),
                    file("tokens.txt", 5_006L, "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"),
                    file("bpe.model", 244_837L, "c8a2a0129c4ab8e463164c142f82d25649661b122c8cd0b7aab5c9e80b90ad24")),
            GIGASPEECH_PHRASES,
            "Apache-2.0",
            "https://www.apache.org/licenses/LICENSE-2.0",
            "sherpa-onnx KWS Zipformer 3.3M, trained on GigaSpeech by pkufool (Xiaomi), Apache-2.0",
            60));

    static {
        validateAll(ALL);
    }

    private WakeModelCatalog() {}

    public static List<WakeModel> all() {
        return ALL;
    }

    /** The entry with this id, or empty; an unknown id is never replaced by another model. */
    public static Optional<WakeModel> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(model -> model.id().equalsIgnoreCase(id)).findFirst();
    }

    /** The only wake model this build ships; the settings and the detector both resolve it here. */
    public static WakeModel defaultModel() {
        return ALL.get(0);
    }

    /** One installed file: the name inside the bundle, its exact size and its lowercase SHA-256. */
    public record WakeFile(String file, long size, String sha256) {}

    /** One wake phrase an owner can choose, with the BPE token line the spotter matches. */
    public record WakePhrase(String text, String tokens) {
        public WakePhrase {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("wake phrase has no text");
            }
            if (tokens == null || tokens.isBlank()) {
                throw new IllegalArgumentException("wake phrase has no tokens: " + text);
            }
            assertTokenLine(tokens);
        }

        /** The phrase the detector reports for a hit, which upper-cases it like the bundle's token table. */
        public String keyword() {
            return normalize(text).toUpperCase(Locale.ROOT);
        }
    }

    /** One wake bundle: where its files come from, exactly which bytes are expected, and which phrases work. */
    public record WakeModel(
            String id,
            String displayName,
            String repo,
            String revision,
            String downloadBaseUrl,
            List<WakeFile> files,
            List<WakePhrase> phrases,
            String license,
            String licenseUrl,
            String attribution,
            int memoryMb) {

        /** Path of the installed bundle, relative to the game directory. */
        public String bundleDir() {
            return BUNDLE_MODELS_DIR + "/" + id;
        }

        /** Bytes the whole bundle transfers before the keywords file is written. */
        public long totalBytes() {
            long total = 0;
            for (WakeFile file : files) {
                total += file.size();
            }
            return total;
        }

        /** The {@code keywords.txt} content: one token line per phrase, exactly what the spotter parses. */
        public String keywordsText() {
            StringBuilder text = new StringBuilder();
            for (WakePhrase phrase : phrases) {
                text.append(phrase.tokens()).append('\n');
            }
            return text.toString();
        }

        /** The phrase matching this text ignoring case and spacing, or empty when the bundle cannot detect it. */
        public Optional<WakePhrase> phrase(String text) {
            String wanted = normalize(text);
            return phrases.stream()
                    .filter(phrase -> normalize(phrase.text()).equals(wanted))
                    .findFirst();
        }

        /** The phrase a blank or unknown setting falls back to. */
        public WakePhrase defaultPhrase() {
            return phrases.get(0);
        }
    }

    /** A setting or model-file name as one comparable phrase: trimmed, single-spaced, lower case. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The keyword a detector reports for this phrase, or empty when the phrase is not in the bundle. */
    public static Optional<String> keywordOf(WakeModel model, String phrase) {
        return model == null ? Optional.empty() : model.phrase(phrase).map(WakePhrase::keyword);
    }

    /**
     * Rejects a manifest that cannot describe a bundle, so a bad entry fails before any request or native load.
     * Called from the static initializer of the catalogue itself.
     */
    public static void validate(WakeModel model) {
        if (model == null) {
            throw new IllegalArgumentException("wake model is missing");
        }
        requireSafeSegment("model id", model.id());
        if (model.displayName() == null || model.displayName().isBlank()) {
            throw new IllegalArgumentException("wake model has no display name: " + model.id());
        }
        if (model.repo() == null || !model.repo().matches("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("unsafe wake repository: " + model.repo());
        }
        if (model.revision() == null || !model.revision().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("wake revision is not an immutable commit: " + model.revision());
        }
        if (model.downloadBaseUrl() == null || !model.downloadBaseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("wake download base is not https: " + model.downloadBaseUrl());
        }
        if (model.bundleDir() == null || model.bundleDir().isEmpty()) {
            throw new IllegalArgumentException("wake bundle directory is empty: " + model.id());
        }
        for (String segment : model.bundleDir().split("[/\\\\]")) {
            requireSafeSegment("bundle directory", segment);
        }
        if (model.files() == null || model.files().isEmpty()) {
            throw new IllegalArgumentException("wake bundle manifest is empty: " + model.id());
        }
        Set<String> files = new HashSet<>();
        for (WakeFile file : model.files()) {
            if (file == null) {
                throw new IllegalArgumentException("empty wake bundle file entry: " + model.id());
            }
            requireSafeSegment("bundle file", file.file());
            if (!files.add(file.file())) {
                throw new IllegalArgumentException("duplicate wake bundle file: " + file.file());
            }
            if (file.size() <= 0) {
                throw new IllegalArgumentException("wake bundle file size is not positive: " + file);
            }
            if (file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("wake bundle hash is not a lowercase SHA-256: " + file.file());
            }
        }
        for (String role : List.of("encoder", "decoder", "joiner", "tokens.txt", "bpe.model")) {
            boolean present =
                    model.files().stream().anyMatch(file -> file.file().contains(role));
            if (!present) {
                throw new IllegalArgumentException("wake bundle is missing '" + role + "': " + model.id());
            }
        }
        if (model.phrases() == null || model.phrases().isEmpty()) {
            throw new IllegalArgumentException("wake model lists no phrases: " + model.id());
        }
        Set<String> phrases = new HashSet<>();
        for (WakePhrase phrase : model.phrases()) {
            if (phrase == null) {
                throw new IllegalArgumentException("empty wake phrase entry: " + model.id());
            }
            if (!phrases.add(normalize(phrase.text()))) {
                throw new IllegalArgumentException("duplicate wake phrase: " + phrase.text());
            }
        }
        if (model.memoryMb() <= 0) {
            throw new IllegalArgumentException("wake model has no memory guidance: " + model.id());
        }
        if (model.license() == null
                || model.license().isBlank()
                || model.licenseUrl() == null
                || model.licenseUrl().isBlank()
                || model.attribution() == null
                || model.attribution().isBlank()) {
            throw new IllegalArgumentException("wake model has no licence or attribution: " + model.id());
        }
    }

    private static void validateAll(List<WakeModel> models) {
        Set<String> ids = new HashSet<>();
        for (WakeModel model : models) {
            validate(model);
            if (!ids.add(model.id().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("duplicate wake model id: " + model.id());
            }
        }
    }

    /**
     * A token line must be the space-separated BPE units the spotter expects: no tabs, no empty units and no
     * uppercase-only surprises beyond what the model's own table contains.
     */
    private static void assertTokenLine(String tokens) {
        for (String unit : tokens.split(" ", -1)) {
            if (unit.isEmpty() || !unit.equals(unit.strip()) || unit.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("wake token line has an empty or padded unit: " + tokens);
            }
        }
    }

    private static void requireSafeSegment(String what, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("unsafe " + what + ": " + value);
        }
    }

    private static WakeFile file(String file, long size, String sha256) {
        return new WakeFile(file, size, sha256);
    }
}
