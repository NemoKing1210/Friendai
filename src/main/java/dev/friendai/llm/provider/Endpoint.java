package dev.friendai.llm.provider;

import java.util.Map;

/**
 * @param baseUrl without a trailing '/'
 * @param extraHeaders sent with every request in addition to the authentication header
 */
public record Endpoint(String baseUrl, String apiKey, AuthStyle authStyle, Map<String, String> extraHeaders) {
    public Endpoint {
        extraHeaders = Map.copyOf(extraHeaders);
    }

    public Endpoint(String baseUrl, String apiKey, AuthStyle authStyle) {
        this(baseUrl, apiKey, authStyle, Map.of());
    }

    @Override
    public String toString() {
        return "Endpoint[baseUrl=" + baseUrl + ", apiKey=***, authStyle=" + authStyle + "]";
    }
}
