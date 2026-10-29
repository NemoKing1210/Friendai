package dev.friendai.protocol;

import dev.friendai.features.memory.MenuStatus;
import dev.friendai.foundation.text.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Shared bounded helpers for typed server-to-client body codecs. */
public final class ProtocolViewBodies {
    public static final int MAX_STATUS_ARGS = 100;
    private static final int MAX_STATUS_KEY_BYTES = 256;
    private static final int MAX_STATUS_ARG_BYTES = 512;

    private ProtocolViewBodies() {}

    public static byte[] encode(ProtocolFamily family, int operationId, long requestId, byte[] body) {
        return ProtocolViewCodec.encode(family, operationId, requestId, body);
    }

    public static <T> DecodeResult<ProtocolView<T>> decode(
            byte[] encoded, ProtocolFamily family, int operationId, Function<ProtocolBodyReader, T> bodyDecoder) {
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
            ProtocolBodyReader body = new ProtocolBodyReader(frame.body());
            T value = bodyDecoder.apply(body);
            body.requireEnd();
            return new DecodeResult.Valid<>(new ProtocolView<>(frame.requestId(), value));
        } catch (ProtocolDataException malformed) {
            return new DecodeResult.Malformed<>(malformed.reason());
        } catch (IllegalArgumentException malformed) {
            return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_FIELD);
        }
    }

    public static void writeStatus(ProtocolBodyWriter out, Optional<MenuStatus> status) {
        out.writeBoolean(status.isPresent());
        if (status.isEmpty()) return;
        MenuStatus value = status.orElseThrow();
        out.writeBoolean(value.ok());
        out.writeString(value.message().key(), MAX_STATUS_KEY_BYTES);
        List<Object> args = value.message().args();
        if (args.size() > MAX_STATUS_ARGS) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        out.writeUnsignedByte(args.size());
        for (Object arg : args) out.writeString(String.valueOf(arg), MAX_STATUS_ARG_BYTES);
    }

    public static Optional<MenuStatus> readStatus(ProtocolBodyReader in) {
        if (!in.readBoolean()) return Optional.empty();
        boolean ok = in.readBoolean();
        String key = in.readString(MAX_STATUS_KEY_BYTES);
        int count = in.readUnsignedByte();
        if (count > MAX_STATUS_ARGS) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        List<Object> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) args.add(in.readString(MAX_STATUS_ARG_BYTES));
        return Optional.of(new MenuStatus(ok, new UserMessage(key, args)));
    }

    public static void writeCount(ProtocolBodyWriter out, int count, int max) {
        if (count < 0 || count > max || count > 0xffff)
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        out.writeUnsignedShort(count);
    }

    public static int readCount(ProtocolBodyReader in, int max) {
        int count = in.readUnsignedShort();
        if (count > max) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        return count;
    }
}
