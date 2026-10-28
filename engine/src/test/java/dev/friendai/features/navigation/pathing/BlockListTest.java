package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockListTest {

    @Test
    void matchesIdsAndTags() {
        List<String> bad = new ArrayList<>();
        BlockList list = BlockList.parse(List.of("#minecraft:planks", "minecraft:obsidian", "not an id"), bad::add);

        assertTrue(list.matches("minecraft:oak_planks", Set.of("minecraft:planks")));
        assertTrue(list.matches("minecraft:obsidian", Set.of()));
        assertFalse(list.matches("minecraft:stone", Set.of("minecraft:base_stone_overworld")));
        assertEquals(List.of("not an id"), bad);
    }

    @Test
    void emptyListMatchesNothing() {
        BlockList list = BlockList.parse(List.of(), entry -> {});
        assertFalse(list.matches("minecraft:dirt", Set.of("minecraft:dirt")));
    }
}
