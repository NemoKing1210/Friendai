package dev.friendai.protocol;

import java.util.Objects;

/** A decoded request together with the frame ID needed to correlate its response. */
public record DecodedClientRequest(long requestId, ClientRequest request) {
    public DecodedClientRequest {
        if (requestId <= 0) throw new IllegalArgumentException("request ID must be positive");
        Objects.requireNonNull(request, "request");
    }
}
