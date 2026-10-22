package dev.friedai.llm.provider;

/** @param baseUrl without a trailing '/' */
public record Endpoint(String baseUrl, String apiKey, AuthStyle authStyle) {
    @Override
    public String toString() {
        return "Endpoint[baseUrl=" + baseUrl + ", apiKey=***, authStyle=" + authStyle + "]";
    }
}
