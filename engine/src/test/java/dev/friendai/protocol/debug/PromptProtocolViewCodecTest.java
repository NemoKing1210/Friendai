package dev.friendai.protocol.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import org.junit.jupiter.api.Test;

final class PromptProtocolViewCodecTest {
    @Test
    void framesPromptAndEnforcesUtf8ByteLimit() {
        String prompt = "Привет 😀";
        byte[] encoded = PromptProtocolViewCodec.encode(prompt, 17);
        DecodeResult.Valid<ProtocolView<String>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, PromptProtocolViewCodec.decode(encoded));
        assertEquals(17, decoded.value().requestId());
        assertEquals(prompt, decoded.value().value());
        assertThrows(IllegalArgumentException.class, () -> PromptProtocolViewCodec.encode("😀".repeat(8_192), 1));
    }
}
