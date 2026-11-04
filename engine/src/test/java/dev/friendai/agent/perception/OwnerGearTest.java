package dev.friendai.agent.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.perception.OwnerGear.Piece;
import dev.friendai.agent.perception.OwnerGear.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerGearTest {
    @Test
    void nothingHeldAndNothingWorn() {
        assertEquals("nothing", OwnerGear.NOTHING.holding());
        assertEquals("nothing", OwnerGear.NOTHING.wearing());
    }

    @Test
    void mainHandOnly() {
        OwnerGear gear = new OwnerGear("iron_pickaxe", "", List.of());
        assertEquals("iron_pickaxe", gear.holding());
    }

    @Test
    void mainHandAndOffhand() {
        OwnerGear gear = new OwnerGear("iron_pickaxe", "shield", List.of());
        assertEquals("iron_pickaxe, offhand shield", gear.holding());
    }

    @Test
    void offhandOnlyStillNamesTheEmptyMainHand() {
        OwnerGear gear = new OwnerGear("", "torch", List.of());
        assertEquals("nothing in the main hand, offhand torch", gear.holding());
    }

    @Test
    void wornPiecesAreListedInSlotOrderWhateverOrderTheCallerUses() {
        OwnerGear gear =
                new OwnerGear("", "", List.of(new Piece(Slot.FEET, "iron_boots"), new Piece(Slot.HEAD, "iron_helmet")));
        assertEquals("head iron_helmet, feet iron_boots", gear.wearing());
    }

    @Test
    void anUnwornSlotIsNotNamed() {
        OwnerGear gear = new OwnerGear("", "", List.of(new Piece(Slot.LEGS, "leather_leggings")));
        assertEquals("legs leather_leggings", gear.wearing());
    }

    @Test
    void theArmorListCannotBeChangedAfterTheFact() {
        java.util.ArrayList<Piece> pieces = new java.util.ArrayList<>();
        OwnerGear gear = new OwnerGear("", "", pieces);
        pieces.add(new Piece(Slot.HEAD, "diamond_helmet"));
        assertEquals("nothing", gear.wearing());
    }
}
