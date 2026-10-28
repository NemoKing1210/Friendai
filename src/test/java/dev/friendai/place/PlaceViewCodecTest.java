package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ServerConfig;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.memory.MenuStatus;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceViewCodecTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static PlaceValues values(String name) {
        return new PlaceValues(
                name,
                PlaceKind.HOUSE,
                "",
                OVERWORLD,
                new Pos(1, 64, 2),
                8,
                Set.of(),
                PlaceSource.MANUAL,
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                "a note");
    }

    private static PlaceView viewWith(int places, Optional<MenuStatus> status) {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        for (int i = 0; i < places; i++) {
            registry.remember(values("place " + i), 10 + i);
        }
        return PlaceViewBuilder.build(registry, ServerConfig.defaults().places, status);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] handcrafted(java.util.function.Consumer<DataOutputStream> body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        body.accept(out);
        return bytes.toByteArray();
    }

    @Test
    void roundTripKeepsRowsAndBounds() {
        PlaceView view = viewWith(1, Optional.of(MenuStatus.ok("friendai.menu.places.status.forgotten", "home")));
        PlaceView decoded = PlaceViewCodec.decode(PlaceViewCodec.encode(view));
        assertEquals(view, decoded);
    }

    @Test
    void roundTripWithoutStatus() {
        PlaceView view = viewWith(3, Optional.empty());
        assertEquals(view, PlaceViewCodec.decode(PlaceViewCodec.encode(view)));
        assertTrue(PlaceViewCodec.decode(PlaceViewCodec.encode(view)).status().isEmpty());
    }

    @Test
    void errorStatusSurvives() {
        PlaceView view = viewWith(0, Optional.of(MenuStatus.error("friendai.menu.places.error.name_taken", "home")));
        PlaceView decoded = PlaceViewCodec.decode(PlaceViewCodec.encode(view));
        assertTrue(decoded.status().isPresent());
        assertEquals(false, decoded.status().get().ok());
        assertEquals(
                "friendai.menu.places.error.name_taken",
                decoded.status().get().message().key());
        assertEquals(List.of("home"), decoded.status().get().message().args());
    }

    @Test
    void malformedViewIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlaceViewCodec.decode(new byte[] {1, 2, 3}));
    }

    @Test
    void fittingDropsRowsButNeverExceedsTheLimit() {
        PlaceView big = viewWith(400, Optional.empty());
        byte[] data = PlaceViewCodec.encodeFitting(big, 2_048);
        assertTrue(data.length <= 2_048);
        assertTrue(PlaceViewCodec.decode(data).places().size() < 400);
    }

    @Test
    void fittingKeepsEveryRowWhenItAlreadyFits() {
        PlaceView view = viewWith(2, Optional.empty());
        assertEquals(view, PlaceViewCodec.decode(PlaceViewCodec.encodeFitting(view, 1 << 20)));
    }

    @Test
    void truncatedDataFails() {
        byte[] data = PlaceViewCodec.encode(viewWith(2, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> PlaceViewCodec.decode(Arrays.copyOf(data, data.length / 2)));
    }

    @Test
    void negativePlaceCountFails() {
        byte[] data = handcrafted(out -> {
            try {
                out.writeBoolean(true);
                out.writeInt(-1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> PlaceViewCodec.decode(data));
    }

    @Test
    void tooManyPlacesFails() {
        List<PlaceView.Row> rows = new ArrayList<>();
        for (int i = 1; i <= PlaceViewCodec.MAX_PLACES + 1; i++) {
            rows.add(new PlaceView.Row(
                    i,
                    "p" + i,
                    "friendai.menu.place.kind.house",
                    "",
                    OVERWORLD,
                    0,
                    64,
                    0,
                    8,
                    0,
                    0,
                    "friendai.menu.place.source.manual",
                    ""));
        }
        byte[] data = PlaceViewCodec.encode(new PlaceView(true, rows, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> PlaceViewCodec.decode(data));
    }

    @Test
    void anOverlongStringFails() {
        byte[] data = handcrafted(out -> {
            try {
                out.writeBoolean(true);
                out.writeInt(1);
                out.writeLong(1);
                out.writeInt((1 << 20) + 1);
                out.write(new byte[8]);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> PlaceViewCodec.decode(data));
    }

    @Test
    void trailingBytesFail() {
        byte[] valid = PlaceViewCodec.encode(viewWith(0, Optional.empty()));
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> PlaceViewCodec.decode(extra));
    }

    @Test
    void anUnknownStatusIsNeverInvented() {
        PlaceView decoded = PlaceViewCodec.decode(PlaceViewCodec.encode(viewWith(1, Optional.empty())));
        assertTrue(decoded.status().isEmpty());
    }

    @Test
    void fittingHashesItsWayDownToTheEmptyView() {
        byte[] data = PlaceViewCodec.encodeFitting(viewWith(400, Optional.empty()), 64);
        assertTrue(data.length <= 64);
        assertEquals(0, PlaceViewCodec.decode(data).places().size());
    }

    @Test
    void anEmptyViewRoundTrips() {
        PlaceView view = new PlaceView(false, List.of(), Optional.empty());
        assertEquals(view, PlaceViewCodec.decode(PlaceViewCodec.encode(view)));
    }
}
