package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.protocol.places.PlaceRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MemoryPlaceRequestCodecTest {
    @Test
    void encodesMemoryTrustAsUuidAndCanonicalBoolean() {
        UUID target = new UUID(0x0102030405060708L, 0x1112131415161718L);
        MemoryRequest.TrustSet request = new MemoryRequest.TrustSet(target, true);

        byte[] encoded = ClientRequestCodec.encode(2, request);

        assertArrayEquals(
                new byte[] {
                    1, 2, 7, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 17, 1, 2, 3, 4, 5, 6, 7, 8, 17, 18, 19, 20, 21, 22, 23,
                    24, 1
                },
                encoded);
        assertEquals(
                2,
                ((DecodedClientRequest) ((DecodeResult.Valid<?>) ClientRequestCodec.decode(encoded)).value())
                        .requestId());
        assertEquals(
                request,
                ((DecodedClientRequest) ((DecodeResult.Valid<?>) ClientRequestCodec.decode(encoded)).value())
                        .request());
    }

    @Test
    void transcriptClearIsABodylessMemoryOperation() {
        byte[] encoded = ClientRequestCodec.encode(4, new MemoryRequest.TranscriptClear());
        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(encoded);

        assertInstanceOf(DecodeResult.Valid.class, decoded);
        assertEquals(
                new MemoryRequest.TranscriptClear(),
                ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().request());
        assertEquals(9, new MemoryRequest.TranscriptClear().operationId());
        assertEquals(true, ProtocolFamily.MEMORY.supports(ProtocolDirection.CLIENT_TO_SERVER, 9));
    }

    @Test
    void enforcesMemoryCategoryIdsAndMultibyteTextByteBounds() {
        MemoryRequest.FactAdd max = new MemoryRequest.FactAdd("g".repeat(36), 5, "é".repeat(1_000));
        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(ClientRequestCodec.encode(1, max));

        assertInstanceOf(DecodeResult.Valid.class, decoded);
        assertEquals(
                max,
                ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().request());
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(2, new MemoryRequest.FactAdd("group", 6, "fact")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(3, new MemoryRequest.FactAdd("group", 1, "é".repeat(1_000) + "a")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(4, new MemoryRequest.FactEdit(0, 1, "fact")));
    }

    @Test
    void encodesPlaceRenameWithPerFieldUtf8Bounds() {
        PlaceRequest.Rename max = new PlaceRequest.Rename("é".repeat(64), "ö".repeat(64));
        byte[] encoded = ClientRequestCodec.encode(1, max);

        assertEquals(
                max,
                ((DecodedClientRequest) ((DecodeResult.Valid<?>) ClientRequestCodec.decode(encoded)).value())
                        .request());
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(2, new PlaceRequest.Rename("é".repeat(65), "new")));
    }
}
