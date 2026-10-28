package dev.friendai.features.speech.tts;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The local voices the mod offers. Adding a voice is adding an entry here. */
public final class VoiceModels {
    private static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-";

    private static final List<VoiceModel> ALL = List.of(
            model(
                    "ru_RU-irina-medium",
                    "Irina",
                    "ru-RU",
                    67_153_308L,
                    "1fc0f54e5e084fe287c07909f2f6e0ba6d857864cf800e3ab80286a4e8233008",
                    "MIT model / dataset license unknown"),
            model(
                    "ru_RU-dmitri-medium",
                    "Dmitri",
                    "ru-RU",
                    67_188_551L,
                    "c86d0803737de13d441923ff3b3f309482fab8d7af3ec85949942809eb9a3660",
                    "MIT model / dataset license unknown"),
            model(
                    "en_US-lessac-medium",
                    "Lessac",
                    "en-US",
                    67_230_653L,
                    "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
                    "MIT model / Blizzard 2013 dataset license"),
            model(
                    "en_US-libritts_r-medium",
                    "LibriTTS-R",
                    "en-US",
                    82_038_311L,
                    "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c",
                    "CC BY 4.0"),
            model(
                    "en_GB-alba-medium",
                    "Alba",
                    "en-GB",
                    67_212_349L,
                    "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1",
                    "MIT model / CC BY 4.0 dataset"));

    private VoiceModels() {}

    public static List<VoiceModel> all() {
        return ALL;
    }

    public static Optional<VoiceModel> byId(String id) {
        return ALL.stream().filter(model -> model.id().equals(id)).findFirst();
    }

    /** Russian systems get Irina; everything else the English default. */
    public static VoiceModel defaultFor(String gameLanguage) {
        String language = gameLanguage == null ? "" : gameLanguage.toLowerCase(Locale.ROOT);
        String id = language.startsWith("ru") ? "ru_RU-irina-medium" : "en_US-lessac-medium";
        return byId(id).orElseThrow();
    }

    private static VoiceModel model(String id, String name, String language, long size, String sha256, String license) {
        return new VoiceModel(
                id,
                name,
                language,
                BASE + id + ".tar.bz2",
                size,
                sha256,
                license,
                id + ".onnx",
                "tokens.txt",
                "espeak-ng-data");
    }
}
