package dev.friedai.llm.model;

/** A failed LLM call. The message never contains the API key. */
public final class LlmException extends RuntimeException {
    private final LlmErrorKind kind;
    private final int httpStatus;

    public LlmException(LlmErrorKind kind, String message, int httpStatus) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public LlmException(LlmErrorKind kind, String message) {
        this(kind, message, -1);
    }

    public LlmErrorKind kind() {
        return kind;
    }

    /** The HTTP status, or -1 if the failure was not an HTTP response. */
    public int httpStatus() {
        return httpStatus;
    }
}
