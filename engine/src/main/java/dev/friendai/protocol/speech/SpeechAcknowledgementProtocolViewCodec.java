package dev.friendai.protocol.speech;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolBodyReader;
import dev.friendai.protocol.ProtocolBodyWriter;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewBodies;

/** Codec for the server's answer to one admitted voice transcript. */
public final class SpeechAcknowledgementProtocolViewCodec {
    /** Operation 130 inside the speech family, beside the complete (128) and streamed (129) speech views. */
    public static final int OPERATION = 130;

    private SpeechAcknowledgementProtocolViewCodec() {}

    public static byte[] encode(SpeechAcknowledgement acknowledgement, long requestId) {
        ProtocolBodyWriter body = new ProtocolBodyWriter(32);
        body.writeUnsignedByte(acknowledgement.outcome().id());
        return ProtocolViewBodies.encode(ProtocolFamily.SPEECH, OPERATION, requestId, body.toByteArray());
    }

    public static DecodeResult<ProtocolView<SpeechAcknowledgement>> decode(byte[] encoded) {
        return ProtocolViewBodies.decode(
                encoded, ProtocolFamily.SPEECH, OPERATION, SpeechAcknowledgementProtocolViewCodec::decodeBody);
    }

    private static SpeechAcknowledgement decodeBody(ProtocolBodyReader in) {
        SpeechAcknowledgement.Outcome outcome = SpeechAcknowledgement.Outcome.fromId(in.readUnsignedByte());
        if (outcome == null) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        }
        return new SpeechAcknowledgement(outcome);
    }
}
