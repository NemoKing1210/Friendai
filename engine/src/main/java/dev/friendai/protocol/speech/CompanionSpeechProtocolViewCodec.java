package dev.friendai.protocol.speech;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolBodyReader;
import dev.friendai.protocol.ProtocolBodyWriter;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewBodies;

/** Bounded companion-speech event codec using the common speech family frame. */
public final class CompanionSpeechProtocolViewCodec {
    public static final int MAX_FRAME_BYTES = 16_384;
    public static final int MAX_COMPANION_BYTES = 64;
    public static final int MAX_TEXT_BYTES = 2_000;
    public static final int MAX_AUDIBLE_RANGE = 128;

    private CompanionSpeechProtocolViewCodec() {}

    public static byte[] encode(CompanionSpeechView view) {
        return encode(view, 0);
    }

    public static byte[] encode(CompanionSpeechView view, long requestId) {
        if (view.audibleRange() > MAX_AUDIBLE_RANGE) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        ProtocolBodyWriter body = new ProtocolBodyWriter(MAX_FRAME_BYTES - 15);
        body.writeString(view.companion(), MAX_COMPANION_BYTES);
        body.writeString(view.text(), MAX_TEXT_BYTES);
        body.writeUuid(view.addressee());
        body.writeUuid(view.companionId());
        body.writeInt(view.audibleRange());
        return ProtocolViewBodies.encode(ProtocolFamily.SPEECH, 128, requestId, body.toByteArray());
    }

    public static DecodeResult<ProtocolView<CompanionSpeechView>> decode(byte[] encoded) {
        return ProtocolViewBodies.decode(
                encoded, ProtocolFamily.SPEECH, 128, CompanionSpeechProtocolViewCodec::decodeBody);
    }

    private static CompanionSpeechView decodeBody(ProtocolBodyReader body) {
        String companion = body.readString(MAX_COMPANION_BYTES);
        String text = body.readString(MAX_TEXT_BYTES);
        var addressee = body.readUuid();
        var companionId = body.readUuid();
        int range = body.readInt();
        if (range < 0 || range > MAX_AUDIBLE_RANGE) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        return new CompanionSpeechView(companion, text, addressee, companionId, range);
    }
}
