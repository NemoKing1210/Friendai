package dev.friendai.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Strict UTF-8 JSON body support for bounded, record-shaped protocol views. */
public final class ProtocolJsonViewCodec {
    private ProtocolJsonViewCodec() {}

    public static void requireUtf8(String value, int maxBytes) {
        ProtocolBodyWriter validator = new ProtocolBodyWriter(Math.max(2, maxBytes + 2));
        validator.writeString(value, maxBytes);
    }

    public static byte[] encodeBody(Object value, Type type, Gson gson, int maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("negative JSON view bound");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        OutputStream bounded = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                requireCapacity(1);
                bytes.write(value);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                requireCapacity(length);
                bytes.write(buffer, offset, length);
            }

            private void requireCapacity(int length) throws IOException {
                if (length < 0 || length > maxBytes - bytes.size()) throw new IOException("JSON view exceeds limit");
            }
        };
        try (OutputStreamWriter writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) {
            gson.toJson(value, type, writer);
        } catch (IOException oversized) {
            throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        }
        return bytes.toByteArray();
    }

    public static <T> T decodeBody(byte[] body, Type type, Gson gson) {
        if (body == null) throw new ProtocolDataException(DecodeResult.Reason.TRUNCATED_FRAME);
        String json;
        try {
            json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_UTF8);
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            T value = gson.fromJson(reader, type);
            if (value == null || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            return value;
        } catch (IOException | JsonParseException malformed) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        }
    }

    public static <T> DecodeResult<ProtocolView<T>> decode(
            byte[] encoded, ProtocolFamily family, int operationId, Type type, Gson gson, Consumer<T> validator) {
        DecodeResult<ProtocolFrame> decoded = ProtocolViewCodec.decode(encoded);
        if (decoded instanceof DecodeResult.Malformed<ProtocolFrame> malformed) {
            return new DecodeResult.Malformed<>(malformed.reason());
        }
        if (decoded instanceof DecodeResult.Unsupported<ProtocolFrame> unsupported) {
            return new DecodeResult.Unsupported<>(unsupported.reason());
        }
        ProtocolFrame frame = ((DecodeResult.Valid<ProtocolFrame>) decoded).value();
        if (frame.familyId() != family.id()) return new DecodeResult.Unsupported<>(DecodeResult.Reason.UNKNOWN_FAMILY);
        if (frame.operationId() != operationId)
            return new DecodeResult.Unsupported<>(DecodeResult.Reason.UNKNOWN_OPERATION);
        try {
            T value = decodeBody(frame.body(), type, gson);
            validator.accept(value);
            return new DecodeResult.Valid<>(new ProtocolView<>(frame.requestId(), value));
        } catch (ProtocolDataException malformed) {
            return new DecodeResult.Malformed<>(malformed.reason());
        } catch (RuntimeException malformed) {
            return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_FIELD);
        }
    }
}
