package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkSetOrderTest {
    @Test
    void firstOccurrenceWinsAndLaterDuplicatesAreIgnored() {
        assertEquals(Map.of("a", 0, "b", 1), WorkSetOrder.firstIndex(List.of("a", "b", "a")));
    }

    @Test
    void emptyOrderHasNoIndexes() {
        assertEquals(Map.of(), WorkSetOrder.firstIndex(List.of()));
        assertEquals(Map.of(), WorkSetOrder.firstIndex(null));
    }
}
