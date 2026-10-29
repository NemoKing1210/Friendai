package dev.friendai.protocol;

import java.util.Objects;

public record ProtocolFrame(int version, int familyId, int operationId, long requestId, byte[] body) {
    public ProtocolFrame {
        Objects.requireNonNull(body, "body");
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
