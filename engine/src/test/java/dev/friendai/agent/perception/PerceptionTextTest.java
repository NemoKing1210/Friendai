package dev.friendai.agent.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.combat.Threat;
import dev.friendai.features.knowledge.Landmark;
import dev.friendai.features.survival.selfcare.EffectMark;
import dev.friendai.features.survival.selfcare.SelfCareEffect;
import dev.friendai.features.survival.selfcare.SelfCareStock;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerceptionTextTest {
    @Test
    void selfStatusLineIsCompact() {
        SelfStatus dry = new SelfStatus(true, false, false, false, false, true, 15, "stone at 1 63 2", 4);
        assertEquals(
                "Self: on the ground; light 15, open sky; underfoot: stone at 1 63 2; headroom 4+; health 100%",
                dry.toPromptText());

        SelfStatus wet = new SelfStatus(
                false, true, false, false, false, false, 3, "in the air above stone at 1 63 2 (2 blocks below)", 0);
        assertEquals(
                "Self: in water, airborne; light 3, no sky above; "
                        + "underfoot: in the air above stone at 1 63 2 (2 blocks below); headroom 0; health 100%",
                wet.toPromptText());
    }

    @Test
    void selfStatusNamesEffectsFallsAndEmergencyItems() {
        SelfStatus hurt = new SelfStatus(
                false,
                false,
                false,
                false,
                false,
                true,
                15,
                "stone at 1 63 2",
                4,
                0.25F,
                6.5F,
                List.of(
                        new SelfCareEffect("minecraft:poison", 0, 240, EffectMark.HARMFUL),
                        new SelfCareEffect("minecraft:speed", 1, 1200, EffectMark.BENEFICIAL)),
                new SelfCareStock(1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(
                "Self: airborne; light 15, open sky; underfoot: stone at 1 63 2; headroom 4+; health 25%; "
                        + "falling 6.5 blocks\n"
                        + "Effects: minecraft:poison 12 s, minecraft:speed 60 s\n"
                        + "Emergency: water bucket, milk bucket, 2 golden apples",
                hurt.toPromptText());
    }

    @Test
    void threatLineNamesTarget() {
        assertEquals(
                "zombie 4.2 blocks behind-right, 18/20 hp, coming for me",
                new Threat("zombie", 4.2, "behind-right", 18f, 20f, "me", true).toPromptText());
        assertEquals(
                "skeleton 9.0 blocks north, 20/20 hp, not targeting anyone",
                new Threat("skeleton", 9.0, "north", 20f, 20f, "nothing", true).toPromptText());
        assertEquals(
                "creeper 3.0 blocks left, 20/20 hp, coming for the owner",
                new Threat("creeper", 3.0, "left", 20f, 20f, "owner", true).toPromptText());
    }

    @Test
    void landmarkLineHasPositionAndDirection() {
        assertEquals(
                "crafting_table at 12 64 -3, 4.1 blocks south-east",
                new Landmark("crafting_table", "crafting_table", "12 64 -3", 4.1, "south-east").toPromptText());
    }
}
