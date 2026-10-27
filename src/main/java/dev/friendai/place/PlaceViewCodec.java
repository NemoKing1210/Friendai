package dev.friendai.place;

import dev.friendai.memory.MenuStatus;
import dev.friendai.text.UserMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure-Java wire format for {@link PlaceView}. Decoding failures are {@link IllegalArgumentException}. */
public final class PlaceViewCodec {
    public static final int MAX_PLACES = 512;
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_ARGS = 100;

    private PlaceViewCodec() {}

    public static byte[] encode(PlaceView view) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBoolean(view.enabled());
            out.writeInt(view.places().size());
            for (PlaceView.Row row : view.places()) {
                out.writeLong(row.id());
                writeString(out, row.name());
                writeString(out, row.kindKey());
                writeString(out, row.label());
                writeString(out, row.dimension());
                out.writeInt(row.x());
                out.writeInt(row.y());
                out.writeInt(row.z());
                out.writeInt(row.radius());
                out.writeInt(row.visits());
                out.writeLong(row.lastVisitTick());
                writeString(out, row.sourceKey());
                writeString(out, row.note());
            }
            if (view.status().isPresent()) {
                MenuStatus status = view.status().get();
                out.writeBoolean(true);
                out.writeBoolean(status.ok());
                writeString(out, status.message().key());
                out.writeInt(status.message().args().size());
                for (Object arg : status.message().args()) {
                    writeString(out, String.valueOf(arg));
                }
            } else {
                out.writeBoolean(false);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encodes a view no larger than {@code maxBytes}. Places are dropped from the end until it fits, so a book
     * larger than the packet limit degrades to a partial tab instead of a view the client rejects.
     */
    public static byte[] encodeFitting(PlaceView view, int maxBytes) {
        byte[] data = encode(view);
        if (data.length <= maxBytes) {
            return data;
        }
        List<PlaceView.Row> places = view.places();
        int keep = places.size();
        while (keep > 0) {
            keep /= 2;
            data = encode(withPlaces(view, places.subList(0, keep)));
            if (data.length <= maxBytes) {
                return data;
            }
        }
        return data;
    }

    private static PlaceView withPlaces(PlaceView view, List<PlaceView.Row> places) {
        return new PlaceView(view.enabled(), List.copyOf(places), view.status());
    }

    public static PlaceView decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            boolean enabled = in.readBoolean();
            int placeCount = readCount(in, MAX_PLACES);
            List<PlaceView.Row> places = new ArrayList<>(placeCount);
            for (int i = 0; i < placeCount; i++) {
                places.add(new PlaceView.Row(
                        in.readLong(),
                        readString(in),
                        readString(in),
                        readString(in),
                        readString(in),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readLong(),
                        readString(in),
                        readString(in)));
            }
            Optional<MenuStatus> status = Optional.empty();
            if (in.readBoolean()) {
                boolean ok = in.readBoolean();
                String key = readString(in);
                int argCount = readCount(in, MAX_ARGS);
                List<Object> args = new ArrayList<>(argCount);
                for (int i = 0; i < argCount; i++) {
                    args.add(readString(in));
                }
                status = Optional.of(new MenuStatus(ok, new UserMessage(key, args)));
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new PlaceView(enabled, List.copyOf(places), status);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed place view", e);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IllegalArgumentException("bad string length " + length);
        }
        byte[] b = new byte[length];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("bad count " + n);
        }
        return n;
    }
}
