package dev.friendai.features.placements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacedTextTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static PlacedBlock block(int x, String id) {
        return new PlacedBlock(OVERWORLD, new Pos(x, 64, 0), id, 1, "place_block");
    }

    @Test
    void rendersOneLinePerMaterialNearestFirst() {
        List<PlacedBlock> entries =
                List.of(block(1, "minecraft:chest"), block(2, "minecraft:oak_log"), block(3, "minecraft:oak_log"));
        List<PlacedText.Group> groups = PlacedText.group(entries, entry -> "nearby");
        String text = PlacedText.prompt(groups, 400);
        assertTrue(text.startsWith("Blocks you placed that are still in the world"), text);
        int chest = text.indexOf("1 minecraft:chest");
        int logs = text.indexOf("2 minecraft:oak_log");
        assertTrue(chest >= 0 && logs > chest, text);
        assertTrue(text.contains("nearest nearby at 2 64 0 (place_block)"), text);
    }

    @Test
    void dropsWholeLinesOverBudget() {
        List<PlacedText.Group> groups = PlacedText.group(
                List.of(block(1, "minecraft:chest"), block(2, "minecraft:oak_log")), entry -> "nearby");
        String full = PlacedText.prompt(groups, 400);
        String cut = PlacedText.prompt(groups, full.indexOf('\n', full.indexOf('\n') + 1) + 5);
        assertTrue(cut.contains("minecraft:chest"), cut);
        assertFalse(cut.contains("minecraft:oak_log"), cut);
        assertFalse(cut.endsWith("-"), cut);
    }

    @Test
    void emptyGroupsRenderNoBlock() {
        assertEquals("", PlacedText.prompt(List.of(), 400));
    }

    @Test
    void listingNamesTruncationAndDistance() {
        PlacedQuery.Result result = new PlacedQuery.Result(List.of(block(3, "minecraft:oak_log")), 14, true);
        String text = PlacedText.listing(result, "minecraft:oak_log", 32, new Pos(0, 64, 0));
        assertTrue(text.startsWith("1 of 14 minecraft:oak_log you placed within 32 blocks"), text);
        assertTrue(text.contains("truncated"), text);
        assertTrue(text.contains("3.0 blocks, place_block"), text);
    }

    @Test
    void listingSaysWhenNothingMatches() {
        String text = PlacedText.listing(new PlacedQuery.Result(List.of(), 0, false), null, 32, new Pos(0, 64, 0));
        assertEquals("no blocks you placed within 32 blocks", text);
    }
}
