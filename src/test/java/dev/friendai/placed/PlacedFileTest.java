package dev.friendai.placed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacedFileTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static PlacedBlock block(int x, String id, long tick, String source) {
        return new PlacedBlock(OVERWORLD, new Pos(x, 64, 0), id, tick, source);
    }

    @Test
    void roundTripsEveryField() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(1, "minecraft:oak_log", 7, "place_block"));
        book.record(block(2, "minecraft:chest", 9, "craft_item"));
        PlacedBook copy = PlacedFile.decode(PlacedFile.encode(book).toJson(), PlacedLimits.DEFAULTS);
        assertEquals(book.all(), copy.all());
    }

    @Test
    void rejectsWrongVersion() {
        String json = "{\"version\":0,\"entries\":[]}";
        assertThrows(IllegalArgumentException.class, () -> PlacedFile.decode(json, PlacedLimits.DEFAULTS));
    }

    @Test
    void rejectsDuplicatePosition() {
        String json = "{\"version\":1,\"entries\":["
                + "{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":0,"
                + "\"block\":\"minecraft:oak_log\",\"tick\":1,\"source\":\"direct\"},"
                + "{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":0,"
                + "\"block\":\"minecraft:oak_log\",\"tick\":2,\"source\":\"direct\"}]}";
        assertThrows(IllegalArgumentException.class, () -> PlacedFile.decode(json, PlacedLimits.DEFAULTS));
    }

    @Test
    void rejectsInvalidBlockId() {
        String json = "{\"version\":1,\"entries\":["
                + "{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":0,"
                + "\"block\":\"oak log\",\"tick\":1,\"source\":\"direct\"}]}";
        assertThrows(IllegalArgumentException.class, () -> PlacedFile.decode(json, PlacedLimits.DEFAULTS));
    }

    @Test
    void rejectsNegativeTick() {
        String json = "{\"version\":1,\"entries\":["
                + "{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":0,"
                + "\"block\":\"minecraft:oak_log\",\"tick\":-1,\"source\":\"direct\"}]}";
        assertThrows(IllegalArgumentException.class, () -> PlacedFile.decode(json, PlacedLimits.DEFAULTS));
    }

    @Test
    void rejectsMoreEntriesThanTheCap() {
        PlacedBook book = new PlacedBook(new PlacedLimits(4, 1 << 20));
        for (int i = 0; i < 4; i++) {
            book.record(block(i, "minecraft:oak_log", i, "direct"));
        }
        String json = PlacedFile.encode(book).toJson();
        assertThrows(IllegalArgumentException.class, () -> PlacedFile.decode(json, new PlacedLimits(2, 1 << 20)));
    }

    @Test
    void nullJsonIsAnEmptyBook() {
        assertTrue(PlacedFile.decode(null, PlacedLimits.DEFAULTS).all().isEmpty());
    }

    @Test
    void preservesOldestFirstOrder() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(1, "minecraft:oak_log", 1, "direct"));
        book.record(block(2, "minecraft:oak_log", 2, "direct"));
        assertEquals(
                List.of(1, 2),
                PlacedFile.decode(PlacedFile.encode(book).toJson(), PlacedLimits.DEFAULTS).all().stream()
                        .map(b -> b.pos().x())
                        .toList());
    }
}
