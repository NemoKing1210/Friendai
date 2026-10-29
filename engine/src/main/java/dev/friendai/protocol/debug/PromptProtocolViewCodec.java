package dev.friendai.protocol.debug;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolBodyReader;
import dev.friendai.protocol.ProtocolBodyWriter;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewBodies;

/** Bounded prompt view codec; prompt bodies remain operator-gated by the server adapter. */
public final class PromptProtocolViewCodec {
    public static final int MAX_PROMPT_BYTES = 32_767;

    private PromptProtocolViewCodec() {}

    public static byte[] encode(String prompt, long requestId) {
        ProtocolBodyWriter body = new ProtocolBodyWriter(65_536 - 15);
        body.writeString(prompt, MAX_PROMPT_BYTES);
        return ProtocolViewBodies.encode(ProtocolFamily.DEBUG, 128, requestId, body.toByteArray());
    }

    public static DecodeResult<ProtocolView<String>> decode(byte[] encoded) {
        return ProtocolViewBodies.decode(encoded, ProtocolFamily.DEBUG, 128, PromptProtocolViewCodec::decodeBody);
    }

    private static String decodeBody(ProtocolBodyReader body) {
        return body.readString(MAX_PROMPT_BYTES);
    }
}
