package dev.friendai.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CropBookTest {
    @Test
    void looksUpByCropBlock() {
        CropInfo wheat = FarmFixtures.book().byCrop("minecraft:wheat").orElseThrow();
        assertEquals("minecraft:wheat_seeds", wheat.seed());
    }

    @Test
    void looksUpBySeedItem() {
        assertEquals(
                "minecraft:carrots",
                FarmFixtures.book().bySeed("minecraft:carrot").orElseThrow().crop());
    }

    @Test
    void unknownIdsAreEmpty() {
        assertTrue(FarmFixtures.book().byCrop("minecraft:grass").isEmpty());
        assertTrue(FarmFixtures.book().bySeed("minecraft:stick").isEmpty());
    }

    @Test
    void plantableKeepsInsertionOrder() {
        List<String> seeds =
                FarmFixtures.book().plantable().stream().map(CropInfo::seed).toList();
        assertEquals(
                List.of(
                        "minecraft:wheat_seeds",
                        "minecraft:carrot",
                        "minecraft:potato",
                        "minecraft:beetroot_seeds",
                        "minecraft:nether_wart"),
                seeds);
    }

    @Test
    void duplicateCropIdIsRejected() {
        CropInfo one = new CropInfo("minecraft:wheat", "a", "a", 7, Soil.FARMLAND, 0, 1, 1);
        CropInfo two = new CropInfo("minecraft:wheat", "b", "b", 7, Soil.FARMLAND, 0, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> CropBook.of(List.of(one, two)));
    }
}
