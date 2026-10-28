package dev.friendai.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.build.BlockSpec;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class StructureBookTest {
    private static SpecialStructure get(String id) {
        return StructureBook.withBuiltins().get(id).orElseThrow();
    }

    private static Set<String> positions(SpecialStructure s, String state) {
        return s.body().blocks().stream()
                .filter(b -> b.state().equals(state))
                .map(b -> b.x() + "," + b.y() + "," + b.z())
                .collect(Collectors.toSet());
    }

    @Test
    void builtinsAreIronGolemSnowGolemAndNetherPortal() {
        assertEquals(
                List.of("iron_golem", "nether_portal", "snow_golem"),
                StructureBook.withBuiltins().ids());
    }

    @Test
    void snowGolemIsTwoSnowBlocksAndAPumpkinLast() {
        SpecialStructure s = get("snow_golem");
        assertEquals(Set.of("0,0,0", "0,1,0"), positions(s, "minecraft:snow_block"));
        assertEquals(2, s.body().blocks().size());
        Trigger.LastBlock trigger = assertInstanceOf(Trigger.LastBlock.class, s.trigger());
        assertTrue(trigger.state().startsWith("minecraft:carved_pumpkin"));
        assertEquals(new Offset(0, 2, 0), trigger.at());
        assertEquals(new Outcome.EntitySpawned("minecraft:snow_golem", 3), s.outcome());
    }

    @Test
    void ironGolemIsATAndAPumpkinLast() {
        SpecialStructure s = get("iron_golem");
        assertEquals(Set.of("1,0,0", "0,1,0", "1,1,0", "2,1,0"), positions(s, "minecraft:iron_block"));
        assertEquals(4, s.body().blocks().size());
        Trigger.LastBlock trigger = assertInstanceOf(Trigger.LastBlock.class, s.trigger());
        assertTrue(trigger.state().startsWith("minecraft:carved_pumpkin"));
        assertEquals(new Offset(1, 2, 0), trigger.at());
        assertEquals(new Outcome.EntitySpawned("minecraft:iron_golem", 4), s.outcome());
        assertEquals(new Footprint(3, 3, 1, 1), s.footprint());
    }

    @Test
    void netherPortalIsATenBlockObsidianFrame() {
        SpecialStructure s = get("nether_portal");
        assertEquals(
                Set.of("1,0,0", "2,0,0", "1,4,0", "2,4,0", "0,1,0", "0,2,0", "0,3,0", "3,1,0", "3,2,0", "3,3,0"),
                positions(s, "minecraft:obsidian"));
        assertEquals(10, s.body().blocks().size());
        assertEquals(4, s.body().width());
        assertEquals(5, s.body().height());
        assertEquals(1, s.body().depth());
        assertEquals(new Trigger.Ignite("minecraft:flint_and_steel", new Offset(1, 1, 0)), s.trigger());
        assertEquals(new Outcome.BlocksPresent("minecraft:nether_portal", 6), s.outcome());
        assertEquals(List.of("minecraft:flint_and_steel"), s.requiredItems());
        assertFalse(s.allowedInEnd());
    }

    @Test
    void everyBlockAndTriggerLiesInsideTheFootprint() {
        StructureBook book = StructureBook.withBuiltins();
        for (String id : book.ids()) {
            SpecialStructure s = book.get(id).orElseThrow();
            Footprint f = s.footprint();
            for (BlockSpec b : s.body().blocks()) {
                assertTrue(inside(f, b.x(), b.y(), b.z()), id + " block " + b);
            }
            Offset at = switch (s.trigger()) {
                case Trigger.LastBlock t -> t.at();
                case Trigger.Ignite t -> t.at();
            };
            assertTrue(inside(f, at.x(), at.y(), at.z()), id + " trigger " + at);
        }
    }

    @Test
    void duplicateIdIsRejected() {
        StructureBook book = StructureBook.withBuiltins();
        SpecialStructure again = book.get("iron_golem").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> book.register(again));
    }

    private static boolean inside(Footprint f, int x, int y, int z) {
        return x >= 0 && x < f.width() && y >= 0 && y < f.height() && z >= 0 && z < f.depth();
    }
}
