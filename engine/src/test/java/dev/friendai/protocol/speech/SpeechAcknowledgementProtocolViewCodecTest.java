package dev.friendai.protocol.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolDirection;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolFrameCodec;
import dev.friendai.protocol.ProtocolView;
import org.junit.jupiter.api.Test;

final class SpeechAcknowledgementProtocolViewCodecTest {
    @Test
    void everyOutcomeRoundTripsWithTheRequestId() {
        for (SpeechAcknowledgement.Outcome outcome : SpeechAcknowledgement.Outcome.values()) {
            byte[] encoded = SpeechAcknowledgementProtocolViewCodec.encode(new SpeechAcknowledgement(outcome), 41);
            DecodeResult.Valid<ProtocolView<SpeechAcknowledgement>> decoded =
                    assertInstanceOf(DecodeResult.Valid.class, SpeechAcknowledgementProtocolViewCodec.decode(encoded));
            assertEquals(41, decoded.value().requestId());
            assertEquals(outcome, decoded.value().value().outcome());
        }
    }

    @Test
    void anUnknownOutcomeIsRejected() {
        byte[] encoded = SpeechAcknowledgementProtocolViewCodec.encode(
                new SpeechAcknowledgement(SpeechAcknowledgement.Outcome.ACCEPTED), 42);
        encoded[encoded.length - 1] = (byte) 0x7f;
        assertInstanceOf(DecodeResult.Malformed.class, SpeechAcknowledgementProtocolViewCodec.decode(encoded));
    }

    @Test
    void theOperationBelongsToTheNegotiatedSpeechFamilyInOneDirectionOnly() {
        byte[] encoded = SpeechAcknowledgementProtocolViewCodec.encode(
                new SpeechAcknowledgement(SpeechAcknowledgement.Outcome.RATE_LIMITED), 43);
        assertInstanceOf(
                DecodeResult.Valid.class, ProtocolFrameCodec.decode(ProtocolDirection.SERVER_TO_CLIENT, encoded));
        assertFalse(
                ProtocolFamily.SPEECH.supports(
                        ProtocolDirection.CLIENT_TO_SERVER, SpeechAcknowledgementProtocolViewCodec.OPERATION),
                "a client must not send this operation");
    }
}
