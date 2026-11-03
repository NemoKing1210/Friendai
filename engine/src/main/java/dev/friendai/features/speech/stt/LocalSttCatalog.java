package dev.friendai.features.speech.stt;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The local recognition models this mod can install and run.
 *
 * <p>This is the only local-model catalogue: the settings list, the downloader, readiness checks and the recognizer
 * all resolve model ids here. The legacy {@code tiny|base|small} entries keep their whisper.cpp file names, byte
 * sizes, hashes and flat {@link #LEGACY_MODELS_DIR} location, so an existing installation is reused without a new
 * download. The three sherpa-onnx entries carry the exact immutable revision, file sizes and SHA-256 values of their
 * upstream bundles; a value in this file is a verified fact, not an estimate.
 *
 * <p>Download bases are pinned to a commit for the sherpa-onnx entries. Legacy Whisper keeps {@code resolve/main},
 * which is the URL earlier builds already used and therefore the revision those files actually came from.
 *
 * <p>Languages are short tags from the model's own evidence. {@code auto} and a blank setting are always accepted.
 * A model without a language prompt detects the language itself; a model with one receives the tag per stream.
 */
public final class LocalSttCatalog {
    /** Directory the legacy Whisper files were always installed into; it is shared by all three of them. */
    public static final String LEGACY_MODELS_DIR = "friendai/models";

    /** Parent of the per-model bundles the additional recognisers install into. */
    public static final String BUNDLE_MODELS_DIR = "friendai/models/stt";

    public static final String ENGINE_WHISPER = "whisper.cpp";
    public static final String ENGINE_SHERPA = "sherpa-onnx";

    /** sherpa-onnx model family shared by Parakeet, Nemotron and GigaAM. */
    public static final String MODEL_TYPE_NEMO_TRANSDUCER = "nemo_transducer";

    /** Language tag for a model that is not tied to one language. */
    public static final String MULTILINGUAL = "multi";

    /** Language setting that lets the model detect the language. */
    public static final String AUTO_LANGUAGE = "auto";

    private static final String WHISPER_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main";
    private static final String WHISPER_LICENSE = "https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE";
    private static final String WHISPER_ATTRIBUTION =
            "whisper.cpp (c) Georgi Gerganov, MIT; OpenAI Whisper model weights, MIT";

    private static final List<String> PARAKEET_LANGUAGES = List.of(
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt",
            "ro", "sk", "sl", "es", "sv", "ru", "uk");

    /** Locales NVIDIA lists as transcription-ready out of the box; the rest of the 35 need more care. */
    private static final List<String> NEMOTRON_LANGUAGES =
            List.of("en", "es", "fr", "it", "pt", "nl", "de", "tr", "ru", "ar", "hi", "ja", "ko", "vi", "uk");

    private static final List<String> GIGAAM_LANGUAGES = List.of("ru");

    private static final List<LocalSttModel> ALL = List.of(
            whisper(
                    "tiny",
                    "Whisper Tiny",
                    "ggml-tiny.bin",
                    77_691_713L,
                    "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
                    273),
            whisper(
                    "base",
                    "Whisper Base",
                    "ggml-base.bin",
                    147_951_465L,
                    "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
                    390),
            whisper(
                    "small",
                    "Whisper Small",
                    "ggml-small.bin",
                    487_601_967L,
                    "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
                    880),
            sherpa(
                    "parakeet-v3",
                    "Parakeet V3",
                    "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
                    "2bda32ec70b097a55adaa07d9a7173915b43cc78",
                    "decoder.int8.onnx",
                    "joiner.int8.onnx",
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
                                    "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")),
                    PARAKEET_LANGUAGES,
                    false,
                    false,
                    "CC-BY-4.0",
                    "https://creativecommons.org/licenses/by/4.0/",
                    "Parakeet TDT 0.6B v3 (c) NVIDIA, CC BY 4.0; sherpa-onnx export by csukuangfj",
                    790),
            sherpa(
                    "nemotron-3.5",
                    "Nemotron 3.5",
                    "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
                    "ab43d895f5985b1bbab8b6eac8607fcdc05343f3",
                    "decoder.int8.onnx",
                    "joiner.int8.onnx",
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
                                    "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a")),
                    NEMOTRON_LANGUAGES,
                    true,
                    true,
                    "OpenMDW-1.1",
                    "https://openmdw.ai/license/1-1/",
                    "Nemotron 3.5 ASR Streaming 0.6B (c) NVIDIA, OpenMDW-1.1; sherpa-onnx export by csukuangfj2",
                    810),
            sherpa(
                    "gigaam-v3",
                    "GigaAM v3",
                    "csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16",
                    "a6039be7cee829a9044a69ac0ebaf1c191217c97",
                    "decoder.onnx",
                    "joiner.onnx",
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
                                    "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7")),
                    GIGAAM_LANGUAGES,
                    false,
                    false,
                    "MIT",
                    "https://github.com/salute-developers/GigaAM/blob/main/LICENSE",
                    "GigaAM v3 (c) GigaChat Team, MIT; sherpa-onnx export by csukuangfj",
                    340));

    static {
        validateAll(ALL);
    }

    private LocalSttCatalog() {}

    public static List<LocalSttModel> all() {
        return ALL;
    }

    /**
     * The entry with this id, or empty. Resolution is case-insensitive like the legacy Whisper catalogue, but an
     * unknown id is never replaced by another model.
     */
    public static Optional<LocalSttModel> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(model -> model.id().equalsIgnoreCase(id)).findFirst();
    }

    /** Rejects a manifest that cannot describe a bundle, so a bad entry fails before any request or native load. */
    public static void validate(LocalSttModel model) {
        if (model == null) {
            throw new IllegalArgumentException("local speech model is missing");
        }
        requireSafeSegment("model id", model.id());
        if (model.displayName() == null || model.displayName().isBlank()) {
            throw new IllegalArgumentException("model has no display name: " + model.id());
        }
        if (!ENGINE_WHISPER.equals(model.engine()) && !ENGINE_SHERPA.equals(model.engine())) {
            throw new IllegalArgumentException("unknown engine '" + model.engine() + "' for " + model.id());
        }
        if (model.revision() == null || model.revision().isBlank()) {
            throw new IllegalArgumentException("model has no revision: " + model.id());
        }
        if (model.downloadBaseUrl() == null || model.downloadBaseUrl().isBlank()) {
            throw new IllegalArgumentException("model has no download base: " + model.id());
        }
        if (model.bundleDir() == null || model.bundleDir().isEmpty()) {
            throw new IllegalArgumentException("bundle directory is empty: " + model.id());
        }
        for (String segment : model.bundleDir().split("[/\\\\]")) {
            requireSafeSegment("bundle directory", segment);
        }
        if (model.files() == null || model.files().isEmpty()) {
            throw new IllegalArgumentException("bundle manifest is empty: " + model.id());
        }
        Set<String> seen = new HashSet<>();
        for (LocalModelFile file : model.files()) {
            if (file == null) {
                throw new IllegalArgumentException("empty bundle file entry: " + model.id());
            }
            requireSafeSegment("bundle file", file.file());
            if (!seen.add(file.file())) {
                throw new IllegalArgumentException("duplicate bundle file: " + file.file());
            }
            if (file.size() <= 0) {
                throw new IllegalArgumentException("bundle file size is not positive: " + file);
            }
            if (file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("bundle file hash is not a lowercase SHA-256: " + file.file());
            }
        }
        if (ENGINE_WHISPER.equals(model.engine())) {
            if (model.sherpa() != null) {
                throw new IllegalArgumentException("whisper model has sherpa files: " + model.id());
            }
            if (MODEL_TYPE_NEMO_TRANSDUCER.equals(model.modelType())) {
                throw new IllegalArgumentException("whisper model has a sherpa model type: " + model.id());
            }
            if (model.streaming() || model.languagePrompt()) {
                throw new IllegalArgumentException("whisper model claims a sherpa capability: " + model.id());
            }
        } else {
            if (!MODEL_TYPE_NEMO_TRANSDUCER.equals(model.modelType())) {
                throw new IllegalArgumentException("unsupported sherpa model type: " + model.modelType());
            }
            if (model.sherpa() == null) {
                throw new IllegalArgumentException("sherpa model has no transducer files: " + model.id());
            }
            for (String role : List.of(
                    model.sherpa().encoder(),
                    model.sherpa().decoder(),
                    model.sherpa().joiner(),
                    model.sherpa().tokens())) {
                if (!seen.contains(role)) {
                    throw new IllegalArgumentException(
                            "sherpa role '" + role + "' is not in the bundle: " + model.id());
                }
            }
        }
        if (model.streaming() && !model.languagePrompt()) {
            // Nemotron is the only streaming entry and takes a language per stream; a streaming model without
            // one would need a new capability rather than being silently treated as stateless.
            throw new IllegalArgumentException("streaming model does not accept a language: " + model.id());
        }
        if (model.languages() == null || model.languages().isEmpty()) {
            throw new IllegalArgumentException("model lists no languages: " + model.id());
        }
        if (model.memoryMb() <= 0) {
            throw new IllegalArgumentException("model has no memory guidance: " + model.id());
        }
        if (model.license() == null
                || model.license().isBlank()
                || model.licenseUrl() == null
                || model.licenseUrl().isBlank()
                || model.attribution() == null
                || model.attribution().isBlank()) {
            throw new IllegalArgumentException("model has no licence or attribution: " + model.id());
        }
    }

    private static void validateAll(List<LocalSttModel> models) {
        Set<String> ids = new HashSet<>();
        for (LocalSttModel model : models) {
            validate(model);
            if (!ids.add(model.id().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("duplicate local speech model id: " + model.id());
            }
        }
    }

    private static void requireSafeSegment(String what, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("unsafe " + what + ": " + value);
        }
    }

    private static LocalSttModel whisper(
            String id, String displayName, String file, long size, String sha256, int memoryMb) {
        return new LocalSttModel(
                id,
                displayName,
                ENGINE_WHISPER,
                "main",
                WHISPER_BASE,
                LEGACY_MODELS_DIR,
                List.of(file(file, size, sha256)),
                null,
                List.of(MULTILINGUAL),
                false,
                false,
                "whisper.cpp",
                memoryMb,
                "MIT",
                WHISPER_LICENSE,
                WHISPER_ATTRIBUTION);
    }

    private static LocalSttModel sherpa(
            String id,
            String displayName,
            String repo,
            String revision,
            String decoder,
            String joiner,
            List<LocalModelFile> files,
            List<String> languages,
            boolean streaming,
            boolean languagePrompt,
            String license,
            String licenseUrl,
            String attribution,
            int memoryMb) {
        return new LocalSttModel(
                id,
                displayName,
                ENGINE_SHERPA,
                revision,
                "https://huggingface.co/" + repo + "/resolve/" + revision,
                BUNDLE_MODELS_DIR + "/" + id,
                files,
                new SherpaFiles("encoder.int8.onnx", decoder, joiner, "tokens.txt"),
                languages,
                streaming,
                languagePrompt,
                MODEL_TYPE_NEMO_TRANSDUCER,
                memoryMb,
                license,
                licenseUrl,
                attribution);
    }

    private static LocalModelFile file(String file, long size, String sha256) {
        return new LocalModelFile(file, size, sha256);
    }

    /** The four files a sherpa-onnx transducer configuration needs, named as they are inside the bundle. */
    public record SherpaFiles(String encoder, String decoder, String joiner, String tokens) {}

    /** One file of a bundle: its name inside the bundle directory, its size and the SHA-256 it must have. */
    public record LocalModelFile(String file, long size, String sha256) {}

    /**
     * One installable local recogniser.
     *
     * @param engine {@link #ENGINE_WHISPER} or {@link #ENGINE_SHERPA}
     * @param revision immutable upstream revision the download base points at
     * @param downloadBaseUrl base URL of the bundle's files; each file is fetched as {@code base/file}
     * @param modelType sherpa-onnx model family, unused by whisper.cpp
     * @param languages short language tags the model transcribes; {@code auto} and blank are always accepted
     * @param languagePrompt whether the engine reads a per-stream language tag instead of detecting it
     * @param memoryMb qualified guidance for process memory while the model is loaded
     */
    public record LocalSttModel(
            String id,
            String displayName,
            String engine,
            String revision,
            String downloadBaseUrl,
            String bundleDir,
            List<LocalModelFile> files,
            SherpaFiles sherpa,
            List<String> languages,
            boolean streaming,
            boolean languagePrompt,
            String modelType,
            int memoryMb,
            String license,
            String licenseUrl,
            String attribution) {

        /** Aggregate download size of the whole bundle. */
        public long totalBytes() {
            long total = 0;
            for (LocalModelFile file : files) {
                total += file.size();
            }
            return total;
        }

        public boolean whisper() {
            return ENGINE_WHISPER.equals(engine);
        }

        /**
         * Whether the model claims this language. A blank or {@code auto} setting is always acceptable; a model
         * without a language prompt may still detect other languages, but it does not promise them here.
         */
        public boolean supportsLanguage(String tag) {
            if (tag == null || tag.isBlank() || AUTO_LANGUAGE.equalsIgnoreCase(tag)) {
                return true;
            }
            if (languages.contains(MULTILINGUAL)) {
                return true;
            }
            String normalized = tag.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (String language : languages) {
                if (language.equalsIgnoreCase(normalized)
                        || normalized.startsWith(language.toLowerCase(Locale.ROOT) + "-")
                        || language.toLowerCase(Locale.ROOT).startsWith(normalized + "-")) {
                    return true;
                }
            }
            return false;
        }
    }
}
