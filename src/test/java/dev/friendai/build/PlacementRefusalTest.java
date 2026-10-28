package dev.friendai.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacementRefusalTest {
    private static final Pos AT = new Pos(4, 65, -7);

    private static List<PlacementRefusal> all() {
        return List.of(
                new PlacementRefusal.SelfBody(AT, 1),
                new PlacementRefusal.OtherEntity(AT, "minecraft:zombie", 2),
                new PlacementRefusal.ForeignBlock(AT, "minecraft:chest", 1),
                new PlacementRefusal.OwnBlock(AT, "minecraft:dirt", false, 1),
                new PlacementRefusal.Protected(AT, "", "", 0, 0, 0, 1),
                new PlacementRefusal.Protected(AT, "home", "house", 8, 4, -7, 1),
                new PlacementRefusal.NoFace(AT, "minecraft:stone", "", 1),
                new PlacementRefusal.NoSupport(AT, new Pos(4, 64, -7), true, 1),
                new PlacementRefusal.OutOfReach(AT, "boxed in", 1),
                new PlacementRefusal.OutOfMaterial(AT, "minecraft:cobblestone", "gather failed", 1),
                new PlacementRefusal.Rejected(AT, "minecraft:stone", "minecraft:chest", 1),
                new PlacementRefusal.AttemptsExhausted(AT, "no stance left", 3));
    }

    @Test
    void attemptsNeverGoBelowZero() {
        assertEquals(0, new PlacementRefusal.SelfBody(AT, -3).attempts());
        assertEquals(0, new PlacementRefusal.OtherEntity(AT, null, -1).attempts());
    }

    @Test
    void everyKindNamesItsCause() {
        assertTrue(new PlacementRefusal.SelfBody(AT, 1).describe().contains("is where I am standing"));
        assertTrue(new PlacementRefusal.OtherEntity(AT, "minecraft:zombie", 2)
                .describe()
                .contains("minecraft:zombie"));
        assertTrue(new PlacementRefusal.ForeignBlock(AT, "minecraft:chest", 1)
                .describe()
                .contains("minecraft:chest"));
        assertTrue(new PlacementRefusal.OwnBlock(AT, "minecraft:dirt", false, 1)
                .describe()
                .contains("my own"));
        assertTrue(new PlacementRefusal.Protected(AT, "", "", 0, 0, 0, 1)
                .describe()
                .contains("server"));
        assertTrue(new PlacementRefusal.Protected(AT, "home", "house", 8, 4, -7, 1)
                .describe()
                .contains("home"));
        assertTrue(new PlacementRefusal.NoFace(AT, "minecraft:stone", "no neighbour", 1)
                .describe()
                .contains("no neighbour"));
        assertTrue(new PlacementRefusal.NoSupport(AT, new Pos(4, 64, -7), true, 1)
                .describe()
                .contains("4 64 -7"));
        assertTrue(new PlacementRefusal.OutOfReach(AT, "boxed in", 1).describe().contains("boxed in"));
        assertTrue(new PlacementRefusal.OutOfMaterial(AT, "minecraft:cobblestone", "gather failed", 1)
                .describe()
                .contains("gather failed"));
        assertTrue(new PlacementRefusal.Rejected(AT, "minecraft:stone", "minecraft:chest", 1)
                .describe()
                .contains("minecraft:chest"));
        assertTrue(new PlacementRefusal.AttemptsExhausted(AT, "no stance left", 3)
                .describe()
                .contains("no stance left"));
    }

    @Test
    void everyKindHasAStableCauseAndARemedy() {
        for (PlacementRefusal refusal : all()) {
            assertFalse(refusal.cause().isBlank(), refusal.toString());
            assertFalse(refusal.remedy().isBlank(), refusal.toString());
            assertFalse(refusal.describe().isBlank(), refusal.toString());
            assertTrue(refusal.pos() == AT, refusal.toString());
        }
        assertNotEquals(
                new PlacementRefusal.SelfBody(AT, 1).cause(),
                new PlacementRefusal.ForeignBlock(AT, "minecraft:chest", 1).cause());
    }

    @Test
    void anOwnBlockThatMatchesThePlanIsKept() {
        PlacementRefusal.OwnBlock own = new PlacementRefusal.OwnBlock(AT, "minecraft:stone", true, 1);
        assertTrue(own.describe().contains("matching the plan"));
        assertEquals("keep it", own.remedy());
    }
}
