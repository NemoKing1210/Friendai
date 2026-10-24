package dev.friendai.voice.tts;

/** One speech request, before it is turned into an HTTP exchange. The key never appears in {@link #toString()}. */
public record TtsRequest(
        String baseUrl, String apiKey, String model, String voice, String text, double speed) {
    @Override
    public String toString() {
        return "TtsRequest[baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "<none>" : "<redacted>")
                + ", model=" + model + ", voice=" + voice + ", textLength=" + (text == null ? 0 : text.length())
                + ", speed=" + speed + "]";
    }
}
