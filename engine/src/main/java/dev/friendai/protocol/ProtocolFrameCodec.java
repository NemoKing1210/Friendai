package dev.friendai.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

public final class ProtocolFrameCodec {
    private ProtocolFrameCodec() {}

    public static byte[] encode(ProtocolDirection direction, ProtocolFrame frame) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frame, "frame");
        if (frame.version() != ProtocolVersion.CURRENT)
            throw new IllegalArgumentException("unsupported protocol version");
        ProtocolFamily family = ProtocolFamily.fromId(frame.familyId())
                .orElseThrow(() -> new IllegalArgumentException("unknown protocol family"));
        if (!family.supports(direction, frame.operationId()))
            throw new IllegalArgumentException("unknown protocol operation");
        validateRequestId(direction, frame.requestId());
        byte[] body = frame.body();
        int frameLength = Math.addExact(ProtocolVersion.HEADER_BYTES, body.length);
        if (frameLength > family.maxFrameBytes(direction))
            throw new IllegalArgumentException("protocol frame too large");

        return ByteBuffer.allocate(frameLength)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) frame.version())
                .put((byte) family.id())
                .put((byte) frame.operationId())
                .putLong(frame.requestId())
                .putInt(body.length)
                .put(body)
                .array();
    }

    public static DecodeResult<ProtocolFrame> decode(ProtocolDirection direction, byte[] encoded) {
        Objects.requireNonNull(direction, "direction");
        if (encoded == null || encoded.length < ProtocolVersion.HEADER_BYTES)
            return new DecodeResult.Malformed<>(DecodeResult.Reason.TRUNCATED_FRAME);
        if (encoded.length > ProtocolVersion.ABSOLUTE_MAX_FRAME_BYTES)
            return new DecodeResult.Malformed<>(DecodeResult.Reason.OVERSIZED_FRAME);

        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int version = Byte.toUnsignedInt(in.get());
        int familyId = Byte.toUnsignedInt(in.get());
        int operationId = Byte.toUnsignedInt(in.get());
        long requestId = in.getLong();
        int bodyLength = in.getInt();

        if (bodyLength < 0) return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_LENGTH);
        ProtocolFamily family = ProtocolFamily.fromId(familyId).orElse(null);
        int maxFrameBytes = family == null ? ProtocolVersion.ABSOLUTE_MAX_FRAME_BYTES : family.maxFrameBytes(direction);
        if (encoded.length > maxFrameBytes || bodyLength > maxFrameBytes - ProtocolVersion.HEADER_BYTES)
            return new DecodeResult.Malformed<>(DecodeResult.Reason.OVERSIZED_FRAME);
        if (encoded.length != ProtocolVersion.HEADER_BYTES + bodyLength)
            return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_LENGTH);
        if ((direction == ProtocolDirection.CLIENT_TO_SERVER && requestId <= 0)
                || (direction == ProtocolDirection.SERVER_TO_CLIENT && requestId < 0))
            return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_REQUEST_ID);

        if (version != ProtocolVersion.CURRENT)
            return new DecodeResult.Unsupported<>(DecodeResult.Reason.UNSUPPORTED_VERSION);
        if (family == null) return new DecodeResult.Unsupported<>(DecodeResult.Reason.UNKNOWN_FAMILY);
        if (!family.supports(direction, operationId))
            return new DecodeResult.Unsupported<>(DecodeResult.Reason.UNKNOWN_OPERATION);

        byte[] body = Arrays.copyOfRange(encoded, ProtocolVersion.HEADER_BYTES, encoded.length);
        return new DecodeResult.Valid<>(new ProtocolFrame(version, familyId, operationId, requestId, body));
    }

    private static void validateRequestId(ProtocolDirection direction, long requestId) {
        if ((direction == ProtocolDirection.CLIENT_TO_SERVER && requestId <= 0)
                || (direction == ProtocolDirection.SERVER_TO_CLIENT && requestId < 0))
            throw new IllegalArgumentException("invalid protocol request id");
    }
}
