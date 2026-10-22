package dev.friedai.llm.model;

public enum LlmErrorKind {
    AUTH,
    RATE_LIMIT,
    TIMEOUT,
    BAD_REQUEST,
    SERVER,
    NETWORK,
    PARSE;

    public boolean retryable() {
        return this == RATE_LIMIT || this == SERVER || this == NETWORK || this == TIMEOUT;
    }
}
