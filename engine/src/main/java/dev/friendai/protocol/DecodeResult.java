package dev.friendai.protocol;

public sealed interface DecodeResult<T> permits DecodeResult.Valid, DecodeResult.Malformed, DecodeResult.Unsupported {
    record Valid<T>(T value) implements DecodeResult<T> {}

    record Malformed<T>(Reason reason) implements DecodeResult<T> {}

    record Unsupported<T>(Reason reason) implements DecodeResult<T> {}

    enum Reason {
        TRUNCATED_FRAME,
        INVALID_LENGTH,
        OVERSIZED_FRAME,
        INVALID_REQUEST_ID,
        INVALID_FIELD,
        INVALID_UTF8,
        INVALID_BOOLEAN,
        INVALID_ENUM,
        TRAILING_BYTES,
        UNKNOWN_FAMILY,
        UNKNOWN_OPERATION,
        UNSUPPORTED_VERSION
    }
}
