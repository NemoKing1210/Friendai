package dev.friendai.trading;

import com.google.gson.Gson;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Bounded JSON codec for private owner views. */
public final class TradeViewCodec {
    private static final Gson GSON = new Gson();

    private TradeViewCodec() {}

    public static byte[] encode(TradeView view) {
        byte[] data = GSON.toJson(view).getBytes(StandardCharsets.UTF_8);
        if (data.length > TradeView.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("trade view exceeds packet bound");
        return data;
    }

    public static Optional<TradeView> decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > TradeView.MAX_PACKET_BYTES) return Optional.empty();
        try {
            String json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
            if (!boundedNesting(json)) return Optional.empty();
            TradeView view = GSON.fromJson(json, TradeView.class);
            if (view == null) return Optional.empty();
            if (encode(view).length > TradeView.MAX_PACKET_BYTES) return Optional.empty();
            return Optional.of(view);
        } catch (java.nio.charset.CharacterCodingException | RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static boolean boundedNesting(String text) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 64) return false;
            } else if (c == '}' || c == ']') {
                if (--depth < 0) return false;
            }
        }
        return !quoted && depth == 0;
    }
}
