package dev.friendai.protocol;

import java.util.Objects;

/** Frames typed server-to-client views with the shared protocol header. */
public final class ProtocolViewCodec {
    private ProtocolViewCodec() {}

    public static byte[] encode(ProtocolFamily family, int operationId, long requestId, byte[] body) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(body, "body");
        return ProtocolFrameCodec.encode(
                ProtocolDirection.SERVER_TO_CLIENT,
                new ProtocolFrame(ProtocolVersion.CURRENT, family.id(), operationId, requestId, body));
    }

    public static DecodeResult<ProtocolFrame> decode(byte[] encoded) {
        return ProtocolFrameCodec.decode(ProtocolDirection.SERVER_TO_CLIENT, encoded);
    }
}
