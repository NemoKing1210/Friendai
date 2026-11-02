package dev.friendai.protocol.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.friendai.features.cartography.TerrainMapView;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TerrainMapProtocolCodecTest {
    @Test
    void aBoundedViewRoundTripsAtItsOwnOperation() {
        TerrainMapView view = new TerrainMapView(
                9,
                List.of(new TerrainMapView.Cell(3, 4, "minecraft:plains", true, true, false)),
                List.of(new TerrainMapView.Site(3, 4, "village", "oak hollow")),
                List.of(new TerrainMapView.Road(3, 4, 40, 12, "path")));

        byte[] encoded = TerrainMapProtocolCodec.encode(view, 7);
        DecodeResult.Valid<ProtocolView<TerrainMapView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, TerrainMapProtocolCodec.decode(encoded));
        assertEquals(7, decoded.value().requestId());
        assertEquals(view, decoded.value().value());
    }

    @Test
    void anEmptyViewIsStillAValidFrame() {
        byte[] encoded = TerrainMapProtocolCodec.encode(TerrainMapView.EMPTY, 0);
        DecodeResult.Valid<ProtocolView<TerrainMapView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, TerrainMapProtocolCodec.decode(encoded));
        assertEquals(TerrainMapView.EMPTY, decoded.value().value());
    }
}
