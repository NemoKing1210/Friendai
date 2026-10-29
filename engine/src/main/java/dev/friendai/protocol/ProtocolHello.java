package dev.friendai.protocol;

import java.util.Objects;

public record ProtocolHello(int version, ProtocolCapabilities capabilities) {
    public ProtocolHello {
        if (version < 0 || version > 255)
            throw new IllegalArgumentException("protocol version is not an unsigned byte");
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
