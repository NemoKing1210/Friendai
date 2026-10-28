package dev.friendai.client.audio;

/** The whisper.cpp models offered in the settings. Sizes and hashes are from huggingface.co/ggerganov/whisper.cpp. */
public enum WhisperModel {
    TINY(
            "tiny",
            new ModelSpec(
                    "ggml-tiny.bin", 77_691_713L, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21")),
    BASE(
            "base",
            new ModelSpec(
                    "ggml-base.bin", 147_951_465L, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe")),
    SMALL(
            "small",
            new ModelSpec(
                    "ggml-small.bin",
                    487_601_967L,
                    "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"));

    public static final String DOWNLOAD_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main";

    private final String id;
    private final ModelSpec spec;

    WhisperModel(String id, ModelSpec spec) {
        this.id = id;
        this.spec = spec;
    }

    public String id() {
        return id;
    }

    public ModelSpec spec() {
        return spec;
    }

    /** The model with the given id; BASE when the id is unknown. */
    public static WhisperModel byId(String id) {
        for (WhisperModel m : values()) {
            if (m.id.equalsIgnoreCase(id)) {
                return m;
            }
        }
        return BASE;
    }
}
