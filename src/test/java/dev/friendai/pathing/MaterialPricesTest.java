package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MaterialPricesTest {

    @Test
    void cheapIdsCostOne() {
        assertEquals(1, MaterialPrices.price("minecraft:dirt", Set.of("minecraft:dirt")));
        assertEquals(1, MaterialPrices.price("minecraft:cobblestone", Set.of()));
        assertEquals(1, MaterialPrices.price("minecraft:tuff", Set.of()));
    }

    @Test
    void naturalTaggedBlocksCostFour() {
        assertEquals(4, MaterialPrices.price("minecraft:stone", Set.of("minecraft:base_stone_overworld")));
        assertEquals(4, MaterialPrices.price("minecraft:oak_log", Set.of("minecraft:logs")));
    }

    @Test
    void craftedBlocksCostSixteen() {
        assertEquals(16, MaterialPrices.price("minecraft:oak_planks", Set.of("minecraft:planks")));
        assertEquals(16, MaterialPrices.price("minecraft:bricks", Set.of()));
    }
}
