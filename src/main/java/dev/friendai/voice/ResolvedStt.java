package dev.friendai.voice;

/** The recognition settings a protocol adapter needs, with the key already resolved. */
public record ResolvedStt(
        String providerId,
        String protocol,
        String baseUrl,
        String apiKey,
        String model,
        String language,
        String folderId) {

    public boolean local() {
        return "local".equals(protocol);
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never prints the key (rule 7). */
    @Override
    public String toString() {
        return "ResolvedStt[providerId=" + providerId + ", protocol=" + protocol + ", baseUrl=" + baseUrl
                + ", apiKey=" + (hasKey() ? "***" : "") + ", model=" + model + ", language=" + language
                + ", folderId=" + folderId + "]";
    }
}
