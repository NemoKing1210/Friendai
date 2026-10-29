package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ProtocolViewCodecTest {
    @Test
    void framesServerViewsWithFamilyOperationAndCorrelatedRequestId() {
        byte[] body = {0x11, 0x22};
        byte[] encoded = ProtocolViewCodec.encode(ProtocolFamily.TRADING, 128, 42, body);

        assertArrayEquals(new byte[] {1, 5, (byte) 128, 0, 0, 0, 0, 0, 0, 0, 42, 0, 0, 0, 2, 0x11, 0x22}, encoded);
        DecodeResult<ProtocolFrame> decoded = ProtocolViewCodec.decode(encoded);
        assertInstanceOf(DecodeResult.Valid.class, decoded);
        ProtocolFrame frame = (ProtocolFrame) ((DecodeResult.Valid<?>) decoded).value();
        assertEquals(5, frame.familyId());
        assertEquals(128, frame.operationId());
        assertEquals(42, frame.requestId());
        assertArrayEquals(body, frame.body());
    }

    @Test
    void allowsUnsolicitedServerViewAndRefusesClientOnlyOperations() {
        byte[] encoded = ProtocolViewCodec.encode(ProtocolFamily.CONTROL, 128, 0, new byte[0]);

        assertInstanceOf(DecodeResult.Valid.class, ProtocolViewCodec.decode(encoded));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolViewCodec.encode(ProtocolFamily.CONTROL, 3, 1, new byte[0]));
    }
}
