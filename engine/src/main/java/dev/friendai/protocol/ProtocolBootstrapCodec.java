package dev.friendai.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ProtocolBootstrapCodec {
    public static final int MAX_BYTES = 4_096;

    private ProtocolBootstrapCodec() {}

    public static byte[] encodeHello(ProtocolHello hello) {
        return encode(hello.version(), hello.capabilities());
    }

    public static DecodeResult<ProtocolHello> decodeHello(byte[] bytes) {
        DecodeResult<Values> result = decode(bytes);
        if (result instanceof DecodeResult.Malformed<?> malformed)
            return new DecodeResult.Malformed<>(malformed.reason());
        if (result instanceof DecodeResult.Unsupported<?> unsupported)
            return new DecodeResult.Unsupported<>(unsupported.reason());
        Values values = (Values) ((DecodeResult.Valid<?>) result).value();
        return new DecodeResult.Valid<>(new ProtocolHello(values.version(), values.capabilities()));
    }

    public static byte[] encodeAccept(ProtocolAccept accept) {
        return encode(accept.version(), accept.capabilities());
    }

    public static DecodeResult<ProtocolAccept> decodeAccept(byte[] bytes) {
        DecodeResult<Values> result = decode(bytes);
        if (result instanceof DecodeResult.Malformed<?> malformed)
            return new DecodeResult.Malformed<>(malformed.reason());
        if (result instanceof DecodeResult.Unsupported<?> unsupported)
            return new DecodeResult.Unsupported<>(unsupported.reason());
        Values values = (Values) ((DecodeResult.Valid<?>) result).value();
        return new DecodeResult.Valid<>(new ProtocolAccept(values.version(), values.capabilities()));
    }

    private static byte[] encode(int version, ProtocolCapabilities capabilities) {
        if (version < 0 || version > 255) throw new IllegalArgumentException("invalid protocol version");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(version);
        out.write(capabilities.ids().size());
        for (String id : capabilities.ids()) {
            byte[] encoded = id.getBytes(StandardCharsets.UTF_8);
            out.write(encoded.length);
            out.writeBytes(encoded);
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("protocol bootstrap payload too large");
        return bytes;
    }

    private static DecodeResult<Values> decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return new DecodeResult.Malformed<>(DecodeResult.Reason.TRUNCATED_FRAME);
        if (bytes.length > MAX_BYTES) return new DecodeResult.Malformed<>(DecodeResult.Reason.OVERSIZED_FRAME);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int version = Byte.toUnsignedInt(in.get());
        int count = Byte.toUnsignedInt(in.get());
        if (count > ProtocolCapabilities.MAX_COUNT)
            return new DecodeResult.Malformed<>(DecodeResult.Reason.OVERSIZED_FRAME);
        List<String> ids = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                if (!in.hasRemaining()) return new DecodeResult.Malformed<>(DecodeResult.Reason.TRUNCATED_FRAME);
                int length = Byte.toUnsignedInt(in.get());
                if (length == 0 || length > ProtocolCapabilities.MAX_ID_BYTES)
                    return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_LENGTH);
                if (length > in.remaining()) return new DecodeResult.Malformed<>(DecodeResult.Reason.TRUNCATED_FRAME);
                ByteBuffer value = in.slice();
                value.limit(length);
                String id = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(value)
                        .toString();
                in.position(in.position() + length);
                ids.add(id);
            }
            if (in.hasRemaining()) return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_LENGTH);
            ProtocolCapabilities capabilities = ProtocolCapabilities.of(ids.toArray(String[]::new));
            if (version != ProtocolVersion.CURRENT)
                return new DecodeResult.Unsupported<>(DecodeResult.Reason.UNSUPPORTED_VERSION);
            return new DecodeResult.Valid<>(new Values(version, capabilities));
        } catch (CharacterCodingException | IllegalArgumentException malformed) {
            return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_LENGTH);
        }
    }

    private record Values(int version, ProtocolCapabilities capabilities) {}
}
