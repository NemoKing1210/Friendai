package dev.friendai.features.speech.stt;

import java.util.List;
import java.util.Optional;

/**
 * The local recognition models this mod can install. The {@code tiny|base|small} entries mirror the legacy
 * whisper.cpp catalogue, so existing installations keep their file names, sizes and hashes; the sherpa-onnx
 * entries add Parakeet V3, Nemotron 3.5 (streaming) and GigaAM v3 beside them.
 *
 * <p>Paths are relative to the game directory. Legacy Whisper files sit flat in {@link #LEGACY_MODELS_DIR};
 * every new bundle gets its own directory under {@link #BUNDLE_MODELS_DIR}.
 *
 * <p>Values marked {@link #UNVERIFIED}, or built from {@code PLACEHOLDER_BYTES} and {@link #placeholderHash(int)},
 * are placeholders, not facts: the byte sizes, hashes, upstream revisions, language capabilities and licences
 * of the three sherpa-onnx entries must be verified and replaced before any download, install check or native
 * load relies on them. Only the legacy Whisper entries carry real sizes and hashes.
 */
public final class LocalSttCatalog {
    /** Directory the legacy Whisper files were always installed into; it is shared by all three of them. */
    public static final String LEGACY_MODELS_DIR = "friendai/models";

    /** Parent of the per-model bundles the additional recognisers install into. */
    public static final String BUNDLE_MODELS_DIR = "friendai/models/stt";

    public static final String ENGINE_WHISPER = "whisper.cpp";
    public static final String ENGINE_SHERPA = "sherpa-onnx";

    /** Language tag for a model that is not tied to one language. */
    public static final String MULTILINGUAL = "multi";

    /** Marks a field that still needs a verified value; it never means "no support". */
    public static final String UNVERIFIED = "unverified";

    /** A size that only satisfies the "greater than zero" shape; real bundle sizes are not verified yet. */
    private static final long PLACEHOLDER_BYTES = 1L;

    private static final List<LocalSttModel> ALL = List.of(
            whisper(
                    "tiny",
                    "Whisper Tiny",
                    "ggml-tiny.bin",
                    77_691_713L,
                    "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
            whisper(
                    "base",
                    "Whisper Base",
                    "ggml-base.bin",
                    147_951_465L,
                    "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
            whisper(
                    "small",
                    "Whisper Small",
                    "ggml-small.bin",
                    487_601_967L,
                    "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
            sherpa("parakeet-v3", "Parakeet V3", List.of(UNVERIFIED), false, "parakeet-v3.int8.onnx"),
            sherpa("nemotron-3.5", "Nemotron 3.5", List.of(UNVERIFIED), true, "nemotron-3.5.int8.onnx"),
            sherpa("gigaam-v3", "GigaAM v3", List.of("ru"), false, "gigaam-v3.int8.onnx"));

    private LocalSttCatalog() {}

    public static List<LocalSttModel> all() {
        return ALL;
    }

    /**
     * The entry with this id, or empty. Resolution is case-insensitive like the legacy Whisper catalogue, but
     * an unknown id is never replaced by another model.
     */
    public static Optional<LocalSttModel> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(model -> model.id().equalsIgnoreCase(id)).findFirst();
    }

    private static LocalSttModel whisper(String id, String displayName, String file, long size, String sha256) {
        // The legacy client downloads from /resolve/main, so that is the revision actually in use.
        return new LocalSttModel(
                id,
                displayName,
                ENGINE_WHISPER,
                "main",
                LEGACY_MODELS_DIR,
                List.of(new LocalModelFile(file, size, sha256)),
                List.of(MULTILINGUAL),
                false,
                "MIT");
    }

    private static LocalSttModel sherpa(
            String id, String displayName, List<String> languages, boolean streaming, String modelFile) {
        // The sherpa-onnx models carry per-bundle licences; none is established for these entries yet.
        return new LocalSttModel(
                id,
                displayName,
                ENGINE_SHERPA,
                UNVERIFIED,
                BUNDLE_MODELS_DIR + "/" + id,
                List.of(
                        new LocalModelFile(modelFile, PLACEHOLDER_BYTES, placeholderHash(id.hashCode())),
                        new LocalModelFile("tokens.txt", PLACEHOLDER_BYTES, placeholderHash(id.hashCode() + 1))),
                languages,
                streaming,
                UNVERIFIED);
    }

    /** A well-shaped but meaningless SHA-256: 64 lowercase hex digits derived from a placeholder ordinal. */
    private static String placeholderHash(int ordinal) {
        String tail = Long.toHexString(ordinal & 0xffffffffL);
        return "0".repeat(64 - tail.length()) + tail;
    }

    /** One file of a bundle: its name inside the bundle directory, its size and the SHA-256 it must have. */
    public record LocalModelFile(String file, long size, String sha256) {}

    /** One installable local recogniser: engine, upstream revision, bundle layout and capabilities. */
    public record LocalSttModel(
            String id,
            String displayName,
            String engine,
            String revision,
            String bundleDir,
            List<LocalModelFile> files,
            List<String> languages,
            boolean streaming,
            String license) {

        /** Aggregate download size of the whole bundle. */
        public long totalBytes() {
            long total = 0;
            for (LocalModelFile file : files) {
                total += file.size();
            }
            return total;
        }
    }
}
