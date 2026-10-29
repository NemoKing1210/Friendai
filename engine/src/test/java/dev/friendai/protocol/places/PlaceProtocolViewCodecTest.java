package dev.friendai.protocol.places;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.friendai.features.places.PlaceView;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PlaceProtocolViewCodecTest {
    @Test
    void framesRoundTripAllCoordinatesAndUnicodeText() {
        PlaceView view = new PlaceView(
                true,
                List.of(new PlaceView.Row(
                        42,
                        "Дом 🏠",
                        "friendai.menu.place.kind.house",
                        "",
                        "minecraft:overworld",
                        -12,
                        64,
                        87,
                        8,
                        4,
                        10_000,
                        "friendai.menu.place.source.manual",
                        "near river")),
                Optional.empty());

        DecodeResult.Valid<ProtocolView<PlaceView>> decoded = assertInstanceOf(
                DecodeResult.Valid.class, PlaceProtocolViewCodec.decode(PlaceProtocolViewCodec.encode(view, 9)));
        assertEquals(9, decoded.value().requestId());
        assertEquals(view, decoded.value().value());
    }

    @Test
    void rejectsTrailingBytesAndFramesFromAnotherFamily() {
        byte[] encoded = PlaceProtocolViewCodec.encode(new PlaceView(true, List.of(), Optional.empty()), 1);
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertInstanceOf(DecodeResult.Malformed.class, PlaceProtocolViewCodec.decode(trailing));

        byte[] wrongFamily = encoded.clone();
        wrongFamily[1] = 2;
        assertInstanceOf(DecodeResult.Unsupported.class, PlaceProtocolViewCodec.decode(wrongFamily));
    }
}
