package dev.friendai.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ProtocolBodyWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int maxBytes;

    public ProtocolBodyWriter() {
        this(ProtocolVersion.ABSOLUTE_MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
    }

    public ProtocolBodyWriter(int maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("negative protocol body limit");
        this.maxBytes = maxBytes;
    }

    public void writeBoolean(boolean value) {
        requireCapacity(1);
        out.write(value ? 1 : 0);
    }

    public void writeUnsignedByte(int value) {
        if (value < 0 || value > 255) throw invalidField();
        requireCapacity(1);
        out.write(value);
    }

    public void writeUnsignedShort(int value) {
        if (value < 0 || value > 0xffff) throw invalidField();
        requireCapacity(Short.BYTES);
        out.write(value >>> 8);
        out.write(value);
    }

    public void writeInt(int value) {
        requireCapacity(Integer.BYTES);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    public void writeLong(long value) {
        requireCapacity(Long.BYTES);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    public void writeUuid(UUID value) {
        if (value == null) throw invalidField();
        writeLong(value.getMostSignificantBits());
        writeLong(value.getLeastSignificantBits());
    }

    public void writeString(String value, int maxBytes) {
        if (value == null || maxBytes < 0 || maxBytes > 0xffff) throw invalidField();
        int length = utf8Length(value, maxBytes);
        requireCapacity(Short.BYTES + length);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining() != length) throw invalidField();
            out.write(length >>> 8);
            out.write(length);
            while (encoded.hasRemaining()) out.write(Byte.toUnsignedInt(encoded.get()));
        } catch (CharacterCodingException malformedUtf16) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_UTF8);
        }
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    private int utf8Length(String value, int maxBytes) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x7f) bytes++;
            else if (c <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new ProtocolDataException(DecodeResult.Reason.INVALID_UTF8);
                }
                bytes += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_UTF8);
            } else bytes += 3;
            if (bytes > maxBytes) throw invalidField();
        }
        return bytes;
    }

    private void requireCapacity(int count) {
        if (count < 0 || count > maxBytes - out.size()) {
            throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        }
    }

    private static ProtocolDataException invalidField() {
        return new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
    }
}
