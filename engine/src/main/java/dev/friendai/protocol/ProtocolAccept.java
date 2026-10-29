package dev.friendai.protocol;

import java.util.Objects;

public record ProtocolAccept(int version, ProtocolCapabilities capabilities) {
    public ProtocolAccept {
        if (version < 0 || version > 255)
            throw new IllegalArgumentException("protocol version is not an unsigned byte");
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
