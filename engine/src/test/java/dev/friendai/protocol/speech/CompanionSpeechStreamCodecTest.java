package dev.friendai.protocol.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolView;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CompanionSpeechStreamCodecTest {
    @Test
    void aStreamSentenceRoundTripsWithItsIdentity() {
        CompanionSpeechStreamView view = new CompanionSpeechStreamView(
                "Ada", "Привет.", UUID.randomUUID(), UUID.randomUUID(), 16, 7, 9, 3, true);
        byte[] encoded = CompanionSpeechProtocolViewCodec.encodeStream(view, 31);

        DecodeResult.Valid<ProtocolView<CompanionSpeechStreamView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSpeechProtocolViewCodec.decodeStream(encoded));
        assertEquals(31, decoded.value().requestId());
        assertEquals(view, decoded.value().value());
        assertEquals(7, decoded.value().value().streamId());
        assertEquals(9, decoded.value().value().sessionId());
        assertEquals(3, decoded.value().value().sequence());
    }

    @Test
    void aCompleteSpeechFrameIsNotAStreamSentence() {
        CompanionSpeechView complete = new CompanionSpeechView("Ada", "Hi", UUID.randomUUID(), UUID.randomUUID(), 16);
        byte[] encoded = CompanionSpeechProtocolViewCodec.encode(complete);
        assertInstanceOf(DecodeResult.Unsupported.class, CompanionSpeechProtocolViewCodec.decodeStream(encoded));
    }

    @Test
    void invalidIdentityAndOversizedTextAreRejectedBeforeTheWire() {
        UUID addressee = UUID.randomUUID();
        UUID companion = UUID.randomUUID();
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionSpeechStreamView("Ada", "x", addressee, companion, 16, 0, 9, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionSpeechStreamView("Ada", "x", addressee, companion, 16, 7, 0, 0, false));
        CompanionSpeechStreamView tooLong = new CompanionSpeechStreamView(
                "Ada",
                "x".repeat(CompanionSpeechProtocolViewCodec.MAX_TEXT_BYTES + 1),
                addressee,
                companion,
                16,
                7,
                9,
                0,
                false);
        assertThrows(ProtocolDataException.class, () -> CompanionSpeechProtocolViewCodec.encodeStream(tooLong));
    }
}
