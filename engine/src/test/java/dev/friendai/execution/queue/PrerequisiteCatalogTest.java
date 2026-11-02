package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class PrerequisiteCatalogTest {
    @Test
    void aFurnaceIsTheDeclaredSmeltingPrerequisite() {
        var list = PrerequisiteCatalog.forAction("smelt_items", new JsonObject());
        assertEquals(1, list.size());
        assertTrue(list.getFirst() instanceof PlanRepair.Station);
        assertEquals("minecraft:furnace within 16 blocks", list.getFirst().describe());
    }

    @Test
    void aBrewingStandIsTheDeclaredBrewingPrerequisite() {
        var list = PrerequisiteCatalog.forAction("brew_potion", new JsonObject());
        assertEquals("minecraft:brewing_stand within 16 blocks", list.getFirst().describe());
    }

    @Test
    void placingABlockDeclaresOneItem() {
        JsonObject args = new JsonObject();
        args.addProperty("block", "minecraft:oak_planks");
        var list = PrerequisiteCatalog.forAction("place_block", args);
        assertEquals("1 minecraft:oak_planks", list.getFirst().describe());
    }

    @Test
    void anUnknownToolHasNoDeclaredPrerequisite() {
        assertTrue(PrerequisiteCatalog.forAction("wait", new JsonObject()).isEmpty());
        assertTrue(PrerequisiteCatalog.forAction(null, new JsonObject()).isEmpty());
    }
}
