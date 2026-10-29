package dev.friendai.transport.payload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ProtocolPayloadTest {
    @Test
    void protocolPayloadsCopyTheirBytesAndRejectOversizedFrames() {
        byte[] source = {1, 2, 3};
        ProtocolRequestC2SPayload request = new ProtocolRequestC2SPayload(source);
        ProtocolViewS2CPayload view = new ProtocolViewS2CPayload(source);
        ProtocolHelloS2CPayload hello = new ProtocolHelloS2CPayload(source);
        ProtocolAcceptC2SPayload accept = new ProtocolAcceptC2SPayload(source);

        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, request.data());
        assertArrayEquals(new byte[] {1, 2, 3}, view.data());
        assertArrayEquals(new byte[] {1, 2, 3}, hello.data());
        assertArrayEquals(new byte[] {1, 2, 3}, accept.data());
        assertThrows(IllegalArgumentException.class, () -> new ProtocolRequestC2SPayload(new byte[(1 << 20) + 1]));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolViewS2CPayload(new byte[(1 << 20) + 1]));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolHelloS2CPayload(new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolAcceptC2SPayload(new byte[4097]));
    }
}
