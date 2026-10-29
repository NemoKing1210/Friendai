package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ProtocolFrameCodecTest {
    @Test
    void writesTheVersionedHeaderAndBodyInBigEndianOrder() {
        ProtocolFrame frame = new ProtocolFrame(1, 1, 12, 1, new byte[] {0x41, (byte) 0xe2, (byte) 0x98});

        assertArrayEquals(
                new byte[] {1, 1, 12, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 3, 0x41, (byte) 0xe2, (byte) 0x98},
                ProtocolFrameCodec.encode(ProtocolDirection.CLIENT_TO_SERVER, frame));
    }

    @Test
    void rejectsTruncatedAndTrailingFrameBytesAsMalformed() {
        byte[] encoded = {1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 7};

        assertInstanceOf(
                DecodeResult.Malformed.class,
                ProtocolFrameCodec.decode(
                        ProtocolDirection.CLIENT_TO_SERVER, Arrays.copyOf(encoded, encoded.length - 1)));
        assertInstanceOf(
                DecodeResult.Malformed.class,
                ProtocolFrameCodec.decode(
                        ProtocolDirection.CLIENT_TO_SERVER, Arrays.copyOf(encoded, encoded.length + 1)));
    }

    @Test
    void classifiesUnknownVersionAndOperationAsUnsupported() {
        byte[] unknownVersion = {2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0};
        byte[] unknownOperation = {1, 1, 99, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0};

        assertInstanceOf(
                DecodeResult.Unsupported.class,
                ProtocolFrameCodec.decode(ProtocolDirection.CLIENT_TO_SERVER, unknownVersion));
        assertInstanceOf(
                DecodeResult.Unsupported.class,
                ProtocolFrameCodec.decode(ProtocolDirection.CLIENT_TO_SERVER, unknownOperation));
    }

    @Test
    void decodesTheFrameFieldsAndCopiesItsBody() {
        byte[] body = {7, 8};
        byte[] encoded = {1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 7, 8};

        DecodeResult<ProtocolFrame> result = ProtocolFrameCodec.decode(ProtocolDirection.CLIENT_TO_SERVER, encoded);
        assertInstanceOf(DecodeResult.Valid.class, result);
        DecodeResult.Valid<?> valid = (DecodeResult.Valid<?>) result;
        ProtocolFrame decoded = assertInstanceOf(ProtocolFrame.class, valid.value());
        assertEquals(1, decoded.version());
        assertEquals(1, decoded.familyId());
        assertEquals(1, decoded.operationId());
        assertEquals(1, decoded.requestId());
        assertArrayEquals(body, decoded.body());
    }

    @Test
    void enforcesDirectionSpecificFamilyFrameCapIncludingHeader() {
        byte[] oversizedBody = new byte[8_178];

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolFrameCodec.encode(
                        ProtocolDirection.CLIENT_TO_SERVER, new ProtocolFrame(1, 1, 1, 1, oversizedBody)));
    }

    @Test
    void acceptsTheLargestControlRequestFrame() {
        byte[] encoded = ProtocolFrameCodec.encode(
                ProtocolDirection.CLIENT_TO_SERVER, new ProtocolFrame(1, 1, 1, 1, new byte[8_177]));

        assertEquals(8_192, encoded.length);
    }

    @Test
    void rejectsNonpositiveRequestIdsAndNegativeBodyLengths() {
        byte[] zeroRequestId = {1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] negativeBodyLength = {1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, -1, -1, -1, -1};

        assertInstanceOf(
                DecodeResult.Malformed.class,
                ProtocolFrameCodec.decode(ProtocolDirection.CLIENT_TO_SERVER, zeroRequestId));
        assertInstanceOf(
                DecodeResult.Malformed.class,
                ProtocolFrameCodec.decode(ProtocolDirection.CLIENT_TO_SERVER, negativeBodyLength));
    }

    @Test
    void allowsUnsolicitedServerViewWithZeroRequestId() {
        byte[] encoded = ProtocolFrameCodec.encode(
                ProtocolDirection.SERVER_TO_CLIENT, new ProtocolFrame(1, 1, 128, 0, new byte[0]));

        assertInstanceOf(
                DecodeResult.Valid.class, ProtocolFrameCodec.decode(ProtocolDirection.SERVER_TO_CLIENT, encoded));
    }
}
