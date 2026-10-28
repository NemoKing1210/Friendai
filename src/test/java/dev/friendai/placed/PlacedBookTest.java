package dev.friendai.placed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacedBookTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static PlacedBlock block(int x, String id, long tick) {
        return new PlacedBlock(OVERWORLD, new Pos(x, 64, 0), id, tick, "place_block");
    }

    @Test
    void recordsOneBlock() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        PlacedBlock placed = block(1, "minecraft:oak_log", 10);
        book.record(placed);
        assertEquals(1, book.size());
        assertEquals(List.of(placed), book.all());
        assertTrue(book.isDirty());
        assertEquals(1, book.revision());
    }

    @Test
    void samePositionUpdatesInsteadOfDuplicating() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(1, "minecraft:oak_log", 10));
        book.record(block(1, "minecraft:oak_log", 20));
        assertEquals(1, book.size());
        assertEquals(20, book.all().getFirst().tick());
    }

    @Test
    void capEvictsTheOldestEntry() {
        PlacedBook book = new PlacedBook(new PlacedLimits(2, 1024));
        book.record(block(1, "minecraft:oak_log", 1));
        book.record(block(2, "minecraft:oak_log", 2));
        book.record(block(3, "minecraft:oak_log", 3));
        assertEquals(2, book.size());
        assertEquals(List.of(2, 3), book.all().stream().map(b -> b.pos().x()).toList());
    }

    @Test
    void pruneRemovesOnlyFailingEntries() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(1, "minecraft:oak_log", 1));
        book.record(block(2, "minecraft:oak_log", 2));
        long before = book.revision();
        int removed = book.prune(entry -> entry.pos().x() == 2);
        assertEquals(1, removed);
        assertEquals(1, book.size());
        assertEquals(2, book.all().getFirst().pos().x());
        assertEquals(before + 1, book.revision());
    }

    @Test
    void removeIsByIdentity() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(1, "minecraft:oak_log", 1));
        assertTrue(book.remove(OVERWORLD, new Pos(1, 64, 0)));
        assertFalse(book.remove(OVERWORLD, new Pos(1, 64, 0)));
        assertEquals(0, book.size());
    }

    @Test
    void queryIsNearestFirstAndTruncates() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(9, "minecraft:oak_log", 1));
        book.record(block(2, "minecraft:oak_log", 2));
        book.record(block(5, "minecraft:oak_log", 3));
        PlacedQuery.Result result = PlacedQuery.find(book, OVERWORLD, new Pos(0, 64, 0), 10, entry -> true, 2);
        assertEquals(
                List.of(2, 5), result.entries().stream().map(b -> b.pos().x()).toList());
        assertEquals(3, result.total());
        assertTrue(result.truncated());
    }

    @Test
    void queryFiltersByDimensionRadiusAndPredicate() {
        PlacedBook book = new PlacedBook(PlacedLimits.DEFAULTS);
        book.record(block(2, "minecraft:oak_log", 1));
        book.record(block(40, "minecraft:oak_log", 2));
        book.record(new PlacedBlock("minecraft:the_nether", new Pos(3, 64, 0), "minecraft:oak_log", 3, "place_block"));
        PlacedQuery.Result result = PlacedQuery.find(
                book, OVERWORLD, new Pos(0, 64, 0), 10, entry -> entry.blockId().equals("minecraft:oak_log"), 8);
        assertEquals(1, result.total());
        assertEquals(2, result.entries().getFirst().pos().x());
    }
}
