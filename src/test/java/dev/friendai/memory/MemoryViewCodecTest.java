package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryViewCodecTest {
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static MemoryView sample(Optional<MenuStatus> status) {
        return new MemoryView(
                true,
                new MemoryLimits(160, 20, 30),
                List.of(
                        new MemoryView.Group("world", "", MemoryView.Role.WORLD, 2, false),
                        new MemoryView.Group(BOB.toString(), "Bob", MemoryView.Role.TRUSTED, 1, true)),
                List.of(
                        new MemoryView.FactRow(7, "world", Category.PLACE, "Base at 1 2 3", ""),
                        new MemoryView.FactRow(9, BOB.toString(), Category.PROFILE, "Likes oak", "Bob")),
                status);
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

    private static void writeHeader(DataOutputStream out, int groups, int facts) throws IOException {
        out.writeBoolean(true);
        out.writeInt(160);
        out.writeInt(20);
        out.writeInt(30);
        out.writeInt(groups);
        out.writeInt(facts);
    }

    @Test
    void roundTrip() {
        MemoryView view = sample(Optional.of(MenuStatus.ok("friendai.menu.memory.status.cleared", 3)));
        MemoryView decoded = MemoryViewCodec.decode(MemoryViewCodec.encode(view));
        assertEquals(view.enabled(), decoded.enabled());
        assertEquals(view.limits(), decoded.limits());
        assertEquals(view.groups(), decoded.groups());
        assertEquals(view.facts(), decoded.facts());
        assertTrue(decoded.status().isPresent());
        assertTrue(decoded.status().get().ok());
        assertEquals("friendai.menu.memory.status.cleared", decoded.status().get().message().key());
        assertEquals(List.of("3"), decoded.status().get().message().args());
    }

    @Test
    void roundTripWithoutStatus() {
        MemoryView view = sample(Optional.empty());
        assertEquals(view, MemoryViewCodec.decode(MemoryViewCodec.encode(view)));
    }

    @Test
    void errorStatusSurvives() {
        MemoryView view = sample(Optional.of(MenuStatus.error("friendai.menu.memory.error.full", "Bob", 20)));
        MemoryView decoded = MemoryViewCodec.decode(MemoryViewCodec.encode(view));
        assertTrue(decoded.status().isPresent());
        assertTrue(!decoded.status().get().ok());
        assertEquals(List.of("Bob", "20"), decoded.status().get().message().args());
    }

    @Test
    void truncatedDataFails() {
        byte[] data = MemoryViewCodec.encode(sample(Optional.empty()));
        byte[] half = Arrays.copyOf(data, data.length / 2);
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(half));
    }

    @Test
    void negativeCountFails() {
        byte[] data = handcrafted(out -> {
            try {
                writeHeader(out, 0, -1);
                out.writeBoolean(false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(data));
    }

    @Test
    void tooManyFactsFails() {
        List<MemoryView.FactRow> facts = new ArrayList<>();
        for (int i = 1; i <= 2001; i++) {
            facts.add(new MemoryView.FactRow(i, "world", Category.NOTE, "fact " + i, ""));
        }
        MemoryView view = new MemoryView(
                true, MemoryLimits.DEFAULTS, List.of(), facts, Optional.empty());
        byte[] data = MemoryViewCodec.encode(view);
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(data));
    }

    @Test
    void tooManyGroupsFails() {
        List<MemoryView.Group> groups = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            groups.add(new MemoryView.Group(
                    "00000000-0000-0000-0000-" + String.format("%012d", i),
                    "p" + i,
                    MemoryView.Role.GUEST,
                    0,
                    false));
        }
        MemoryView view = new MemoryView(
                true, MemoryLimits.DEFAULTS, groups, List.of(), Optional.empty());
        byte[] data = MemoryViewCodec.encode(view);
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(data));
    }

    @Test
    void badEnumOrdinalFails() {
        byte[] badRole = handcrafted(out -> {
            try {
                writeHeader(out, 1, 0);
                writeString(out, "k");
                writeString(out, "n");
                out.writeInt(99);
                out.writeInt(0);
                out.writeBoolean(false);
                out.writeBoolean(false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(badRole));

        byte[] badCategory = handcrafted(out -> {
            try {
                writeHeader(out, 0, 1);
                out.writeInt(1);
                writeString(out, "world");
                out.writeInt(99);
                writeString(out, "text");
                writeString(out, "");
                out.writeBoolean(false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(badCategory));
    }

    @Test
    void trailingBytesFail() {
        byte[] data = MemoryViewCodec.encode(sample(Optional.empty()));
        byte[] longer = Arrays.copyOf(data, data.length + 1);
        assertThrows(IllegalArgumentException.class, () -> MemoryViewCodec.decode(longer));
    }
}
