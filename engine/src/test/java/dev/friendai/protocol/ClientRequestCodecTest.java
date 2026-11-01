package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.protocol.control.ControlRequest;
import org.junit.jupiter.api.Test;

final class ClientRequestCodecTest {
    @Test
    void encodesTypedBooleanRequestWithExplicitFamilyOperationAndRequestId() {
        byte[] encoded = ClientRequestCodec.encode(1, new ControlRequest.SetAutoRespawn(false));

        assertArrayEquals(new byte[] {1, 1, 8, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0}, encoded);
        DecodeResult<DecodedClientRequest> result = ClientRequestCodec.decode(encoded);
        assertInstanceOf(DecodeResult.Valid.class, result);
        DecodedClientRequest decoded = ((DecodeResult.Valid<DecodedClientRequest>) result).value();
        assertEquals(1, decoded.requestId());
        assertEquals(new ControlRequest.SetAutoRespawn(false), decoded.request());
    }

    @Test
    void roundTripsTheOwnerPauseRequest() {
        byte[] encoded = ClientRequestCodec.encode(2, new ControlRequest.SetPaused(true));

        DecodeResult<DecodedClientRequest> result = ClientRequestCodec.decode(encoded);
        assertInstanceOf(DecodeResult.Valid.class, result);
        DecodedClientRequest decoded = ((DecodeResult.Valid<DecodedClientRequest>) result).value();
        assertEquals(2, decoded.requestId());
        assertEquals(new ControlRequest.SetPaused(true), decoded.request());
    }

    @Test
    void roundTripsTheOwnerRetryRequest() {
        byte[] encoded = ClientRequestCodec.encode(3, new ControlRequest.RetryLast());

        DecodeResult<DecodedClientRequest> result = ClientRequestCodec.decode(encoded);
        assertInstanceOf(DecodeResult.Valid.class, result);
        DecodedClientRequest decoded = ((DecodeResult.Valid<DecodedClientRequest>) result).value();
        assertEquals(3, decoded.requestId());
        assertEquals(new ControlRequest.RetryLast(), decoded.request());
    }

    @Test
    void rejectsNoncanonicalBooleanAndMalformedUtf8BeforeDispatch() {
        byte[] invalidBoolean = {1, 1, 8, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 2};
        byte[] invalidUtf8Rename = {1, 1, 7, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 3, 0, 1, (byte) 0xff};

        assertInstanceOf(DecodeResult.Malformed.class, ClientRequestCodec.decode(invalidBoolean));
        assertInstanceOf(DecodeResult.Malformed.class, ClientRequestCodec.decode(invalidUtf8Rename));
    }

    @Test
    void enforcesRenameAndToolArgumentBoundsInEncodedUtf8Bytes() {
        String maxName = "é".repeat(64);
        byte[] rename = ClientRequestCodec.encode(1, new ControlRequest.Rename(maxName));
        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(rename);

        assertInstanceOf(DecodeResult.Valid.class, decoded);
        assertEquals(
                1, ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().requestId());
        assertEquals(
                new ControlRequest.Rename(maxName),
                ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().request());
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(2, new ControlRequest.Rename(maxName + "é")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(3, new ControlRequest.InvokeTool("tool", "x".repeat(2_001))));
        ControlRequest.InvokeTool maxArgs = new ControlRequest.InvokeTool("tool", "x".repeat(2_000));
        DecodeResult<DecodedClientRequest> maxArgsDecoded =
                ClientRequestCodec.decode(ClientRequestCodec.encode(4, maxArgs));
        assertEquals(
                4,
                ((DecodeResult.Valid<DecodedClientRequest>) maxArgsDecoded)
                        .value()
                        .requestId());
        assertEquals(
                maxArgs,
                ((DecodeResult.Valid<DecodedClientRequest>) maxArgsDecoded)
                        .value()
                        .request());
    }
}
