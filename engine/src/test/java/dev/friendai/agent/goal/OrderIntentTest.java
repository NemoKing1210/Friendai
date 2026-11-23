package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderIntentTest {
    private static OrderIntent.ItemNeed need(OrderIntent intent, String item) {
        return intent.items().stream()
                .filter(n -> n.item().equals(item))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void readsAGiveOrderAsAnOwnerShare() {
        OrderIntent intent = OrderIntent.parse("give me 4 oak logs");
        assertTrue(intent.recognised());
        assertEquals(1, intent.items().size());
        assertEquals(4, need(intent, "minecraft:oak_log").ownerCount());
        assertEquals(0, need(intent, "minecraft:oak_log").companionCount());
        assertFalse(intent.possession());
    }

    @Test
    void readsASetAsEveryToolForBothOfUs() {
        OrderIntent intent = OrderIntent.parse("make one stone tool set for each of us");
        assertTrue(intent.recognised());
        assertEquals(4, intent.items().size());
        for (String tool : toolIds()) {
            OrderIntent.ItemNeed need = need(intent, tool);
            assertEquals(1, need.ownerCount(), tool);
            assertEquals(1, need.companionCount(), tool);
        }
    }

    @Test
    void readsARetainedShareAndAnExplicitPossessionQuestion() {
        OrderIntent retained = OrderIntent.parse("give me two bread and keep one");
        assertEquals(1, need(retained, "minecraft:bread").ownerCount());
        assertEquals(1, need(retained, "minecraft:bread").companionCount());

        OrderIntent possession = OrderIntent.parse("do I have 3 diamonds?");
        assertTrue(possession.possession());
        assertEquals(3, need(possession, "minecraft:diamond").ownerCount());
        assertEquals(0, need(possession, "minecraft:diamond").companionCount());
    }

    @Test
    void readsABuildWithItsNamedParts() {
        OrderIntent intent = OrderIntent.parse("build a small house with a roof and a door");
        assertTrue(intent.recognised());
        assertEquals(1, intent.builds().size());
        assertEquals("house", intent.builds().getFirst().structure());
        assertTrue(intent.builds().getFirst().parts().contains("roof"));
        assertTrue(intent.builds().getFirst().parts().contains("entrance"));
    }

    @Test
    void aFreeFormOrderIsUnresolvedInsteadOfGuessed() {
        OrderIntent intent = OrderIntent.parse("make the place look nicer");
        assertFalse(intent.recognised());
        assertFalse(intent.unresolved().isEmpty());
    }

    private java.util.List<String> toolIds() {
        return java.util.List.of(
                "minecraft:stone_pickaxe", "minecraft:stone_axe", "minecraft:stone_shovel", "minecraft:stone_sword");
    }
}
