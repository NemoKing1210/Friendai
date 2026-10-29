package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.protocol.trading.TradeRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TradingRequestCodecTest {
    @Test
    void encodesQueryWithExplicitDirectionIdAndBinaryQueryIdentity() {
        TradeRequest.Query request =
                new TradeRequest.Query(new UUID(1, 2), "minecraft:diamond", "{}", TradeRequest.Direction.BUY, 3);
        byte[] encoded = ClientRequestCodec.encode(9, request);

        assertEquals(5, Byte.toUnsignedInt(encoded[1]));
        assertEquals(2, Byte.toUnsignedInt(encoded[2]));
        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(encoded);
        assertInstanceOf(DecodeResult.Valid.class, decoded);
        assertEquals(
                9, ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().requestId());
        assertEquals(
                request,
                ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().request());
    }

    @Test
    void enforcesTradingUtf8JsonAndRefreshCountBounds() {
        TradeRequest.Query maxQuery = new TradeRequest.Query(
                new UUID(0, 1), "i".repeat(128), "x".repeat(8_192), TradeRequest.Direction.SELL, 255);
        byte[] encoded = ClientRequestCodec.encode(1, maxQuery);
        assertEquals(
                maxQuery,
                ((DecodedClientRequest) ((DecodeResult.Valid<?>) ClientRequestCodec.decode(encoded)).value())
                        .request());
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(
                        2,
                        new TradeRequest.Query(
                                new UUID(0, 1), "item", "é".repeat(4_097), TradeRequest.Direction.BUY, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(
                        3,
                        new TradeRequest.Refresh(
                                new UUID(0, 1),
                                java.util.stream.LongStream.rangeClosed(1, 21)
                                        .mapToObj(value -> new UUID(0, value))
                                        .toList())));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(4, new TradeRequest.Query(new UUID(0, 1), "item", "{}", null, 0)));
    }

    @Test
    void roundTripsOrdersWithoutCarryingSenderIdentity() {
        TradeRequest.Submit request = new TradeRequest.Submit(new UUID(0x1234, 0x5678), "{\"kind\":\"buy\"}");
        byte[] encoded = ClientRequestCodec.encode(10, request);
        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(encoded);

        assertInstanceOf(DecodeResult.Valid.class, decoded);
        assertEquals(
                10, ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().requestId());
        assertEquals(
                request,
                ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().request());
    }
}
