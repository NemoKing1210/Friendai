package dev.friendai.protocol.places;

import dev.friendai.features.places.PlaceView;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolBodyReader;
import dev.friendai.protocol.ProtocolBodyWriter;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewBodies;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Versioned, bounded places view codec using the common server-to-client frame. */
public final class PlaceProtocolViewCodec {
    public static final int MAX_PLACES = 512;
    public static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int MAX_NAME_BYTES = 128;
    private static final int MAX_KEY_BYTES = 128;
    private static final int MAX_LABEL_BYTES = 128;
    private static final int MAX_DIMENSION_BYTES = 128;
    private static final int MAX_NOTE_BYTES = 2_000;

    private PlaceProtocolViewCodec() {}

    public static byte[] encode(PlaceView view) {
        return encode(view, 0);
    }

    public static byte[] encode(PlaceView view, long requestId) {
        return ProtocolViewBodies.encode(
                ProtocolFamily.PLACES,
                128,
                requestId,
                encodeBody(view, MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES));
    }

    public static byte[] encodeFitting(PlaceView view, long requestId) {
        return encodeFitting(view, requestId, MAX_FRAME_BYTES);
    }

    public static byte[] encodeFitting(PlaceView view, long requestId, int maxFrameBytes) {
        if (maxFrameBytes < ProtocolVersion.HEADER_BYTES || maxFrameBytes > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("invalid places frame limit");
        List<PlaceView.Row> places = view.places();
        int keep = places.size();
        while (true) {
            PlaceView candidate = withPlaces(view, places.subList(0, keep));
            try {
                byte[] body = encodeBody(candidate, maxFrameBytes - ProtocolVersion.HEADER_BYTES);
                return ProtocolViewBodies.encode(ProtocolFamily.PLACES, 128, requestId, body);
            } catch (ProtocolDataException tooLarge) {
                if (tooLarge.reason() != DecodeResult.Reason.OVERSIZED_FRAME || keep == 0) throw tooLarge;
                keep /= 2;
            }
        }
    }

    public static DecodeResult<ProtocolView<PlaceView>> decode(byte[] encoded) {
        return ProtocolViewBodies.decode(encoded, ProtocolFamily.PLACES, 128, PlaceProtocolViewCodec::decodeBody);
    }

    private static byte[] encodeBody(PlaceView view, int maxBytes) {
        if (view.places().size() > MAX_PLACES) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        ProtocolBodyWriter out = new ProtocolBodyWriter(maxBytes);
        out.writeBoolean(view.enabled());
        ProtocolViewBodies.writeCount(out, view.places().size(), MAX_PLACES);
        for (PlaceView.Row row : view.places()) {
            out.writeLong(row.id());
            out.writeString(row.name(), MAX_NAME_BYTES);
            out.writeString(row.kindKey(), MAX_KEY_BYTES);
            out.writeString(row.label(), MAX_LABEL_BYTES);
            out.writeString(row.dimension(), MAX_DIMENSION_BYTES);
            out.writeInt(row.x());
            out.writeInt(row.y());
            out.writeInt(row.z());
            out.writeInt(row.radius());
            out.writeInt(row.visits());
            out.writeLong(row.lastVisitTick());
            out.writeString(row.sourceKey(), MAX_KEY_BYTES);
            out.writeString(row.note(), MAX_NOTE_BYTES);
        }
        ProtocolViewBodies.writeStatus(out, view.status());
        return out.toByteArray();
    }

    private static PlaceView decodeBody(ProtocolBodyReader in) {
        boolean enabled = in.readBoolean();
        int count = ProtocolViewBodies.readCount(in, MAX_PLACES);
        List<PlaceView.Row> places = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            places.add(new PlaceView.Row(
                    in.readLong(),
                    in.readString(MAX_NAME_BYTES),
                    in.readString(MAX_KEY_BYTES),
                    in.readString(MAX_LABEL_BYTES),
                    in.readString(MAX_DIMENSION_BYTES),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    in.readLong(),
                    in.readString(MAX_KEY_BYTES),
                    in.readString(MAX_NOTE_BYTES)));
        }
        Optional<dev.friendai.features.memory.MenuStatus> status = ProtocolViewBodies.readStatus(in);
        return new PlaceView(enabled, List.copyOf(places), status);
    }

    private static PlaceView withPlaces(PlaceView view, List<PlaceView.Row> places) {
        return new PlaceView(view.enabled(), List.copyOf(places), view.status());
    }
}
