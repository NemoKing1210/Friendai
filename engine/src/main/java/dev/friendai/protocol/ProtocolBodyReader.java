package dev.friendai.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ProtocolBodyReader {
    private final ByteBuffer in;

    public ProtocolBodyReader(byte[] body) {
        if (body == null) throw new IllegalArgumentException("null protocol body");
        in = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
    }

    public boolean readBoolean() {
        int value = readUnsignedByte();
        if (value > 1) throw new ProtocolDataException(DecodeResult.Reason.INVALID_BOOLEAN);
        return value == 1;
    }

    public int readUnsignedByte() {
        requireRemaining(1);
        return Byte.toUnsignedInt(in.get());
    }

    public int readUnsignedShort() {
        requireRemaining(Short.BYTES);
        return Short.toUnsignedInt(in.getShort());
    }

    public int readInt() {
        requireRemaining(Integer.BYTES);
        return in.getInt();
    }

    public long readLong() {
        requireRemaining(Long.BYTES);
        return in.getLong();
    }

    public UUID readUuid() {
        return new UUID(readLong(), readLong());
    }

    public String readString(int maxBytes) {
        requireRemaining(Short.BYTES);
        int length = Short.toUnsignedInt(in.getShort());
        if (length > maxBytes) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        requireRemaining(length);
        ByteBuffer encoded = in.slice();
        encoded.limit(length);
        try {
            String value = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(encoded)
                    .toString();
            in.position(in.position() + length);
            return value;
        } catch (CharacterCodingException malformed) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_UTF8);
        }
    }

    public void requireEnd() {
        if (in.hasRemaining()) throw new ProtocolDataException(DecodeResult.Reason.TRAILING_BYTES);
    }

    private void requireRemaining(int length) {
        if (length < 0 || length > in.remaining()) throw new ProtocolDataException(DecodeResult.Reason.TRUNCATED_FRAME);
    }
}
