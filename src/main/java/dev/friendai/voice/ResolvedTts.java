package dev.friendai.voice;

/** The synthesis settings a protocol adapter needs, with the key already resolved. */
public record ResolvedTts(
        String providerId, String protocol, String baseUrl, String apiKey,
        String model, String voice, String language, String folderId, double speed) {

    public boolean local() {
        return "local".equals(protocol);
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never prints the key (rule 7). */
    @Override
    public String toString() {
        return "ResolvedTts[providerId=" + providerId + ", protocol=" + protocol + ", baseUrl=" + baseUrl
                + ", apiKey=" + (hasKey() ? "***" : "") + ", model=" + model + ", voice=" + voice
                + ", language=" + language + ", folderId=" + folderId + ", speed=" + speed + "]";
    }
}
