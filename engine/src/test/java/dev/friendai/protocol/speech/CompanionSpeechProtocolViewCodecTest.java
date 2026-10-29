package dev.friendai.protocol.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CompanionSpeechProtocolViewCodecTest {
    @Test
    void framesAddressedSpeechAndRejectsNegativeRange() {
        CompanionSpeechView view =
                new CompanionSpeechView("Ada", "Здравствуйте", UUID.randomUUID(), UUID.randomUUID(), 16);
        byte[] encoded = CompanionSpeechProtocolViewCodec.encode(view, 29);

        DecodeResult.Valid<ProtocolView<CompanionSpeechView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSpeechProtocolViewCodec.decode(encoded));
        assertEquals(29, decoded.value().requestId());
        assertEquals(view, decoded.value().value());

        byte[] negativeRange = encoded.clone();
        int rangeStart = 15
                + Short.BYTES
                + "Ada".getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + Short.BYTES
                + "Здравствуйте".getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + 4 * Long.BYTES;
        java.util.Arrays.fill(negativeRange, rangeStart, rangeStart + Integer.BYTES, (byte) 0xff);
        assertInstanceOf(DecodeResult.Malformed.class, CompanionSpeechProtocolViewCodec.decode(negativeRange));
    }
}
