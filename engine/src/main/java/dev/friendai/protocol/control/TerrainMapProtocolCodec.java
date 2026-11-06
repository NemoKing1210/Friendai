package dev.friendai.protocol.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.cartography.view.TerrainMapView;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolJsonViewCodec;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.lang.reflect.Type;

/** Bounded control frame for the compact explored-terrain view. The full grid never travels. */
public final class TerrainMapProtocolCodec {
    public static final int OPERATION = 129;
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int MAX_STRING_BYTES = 128;
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type TYPE = TerrainMapView.class;

    private TerrainMapProtocolCodec() {}

    public static byte[] encode(TerrainMapView view, long requestId) {
        TerrainMapView bounded = (view == null ? TerrainMapView.EMPTY : view).truncated(TerrainMapView.MAX_CELLS);
        validate(bounded);
        byte[] body =
                ProtocolJsonViewCodec.encodeBody(bounded, TYPE, GSON, MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
        return ProtocolViewCodec.encode(ProtocolFamily.CONTROL, OPERATION, requestId, body);
    }

    public static DecodeResult<ProtocolView<TerrainMapView>> decode(byte[] encoded) {
        return ProtocolJsonViewCodec.decode(
                encoded, ProtocolFamily.CONTROL, OPERATION, TYPE, GSON, TerrainMapProtocolCodec::validate);
    }

    private static void validate(TerrainMapView view) {
        if (view.cells().size() > TerrainMapView.MAX_CELLS
                || view.sites().size() > TerrainMapView.MAX_MARKS
                || view.roads().size() > TerrainMapView.MAX_MARKS) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        }
        for (TerrainMapView.Cell cell : view.cells()) {
            text(cell.biome());
        }
        for (TerrainMapView.Site site : view.sites()) {
            text(site.kind());
            text(site.name());
        }
        for (TerrainMapView.Road road : view.roads()) {
            text(road.kind());
        }
    }

    private static void text(String value) {
        if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        }
    }
}
