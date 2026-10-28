package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchHintStoreTest {
    @Test
    void liveHintIsReturnedAndExpiredHintIsDropped() {
        SearchHintStore store = new SearchHintStore();
        UUID owner = UUID.randomUUID();
        SearchHint hint = SearchHint.create(SearchPlanner.Heading.EAST, null, null, null, 0, "forest", 100);
        store.put(owner, hint);

        assertTrue(store.get(owner, 100).isPresent());
        assertTrue(store.get(owner, hint.expiresTick()).isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void clearRemovesTheHint() {
        SearchHintStore store = new SearchHintStore();
        UUID owner = UUID.randomUUID();
        store.put(owner, SearchHint.create(SearchPlanner.Heading.NORTH, null, null, null, 0, "", 0));
        store.clear(owner);
        assertTrue(store.get(owner, 0).isEmpty());
    }
}
