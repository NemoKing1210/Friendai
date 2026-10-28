package dev.friendai.features.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The one prompt sentence a death produces; the model must be able to repeat it without raw ids. */
class DeathRecordTest {
    @Test
    void namesTheKillerWhenThereIsOne() {
        DeathRecord death = new DeathRecord("overworld", 12, 64, -3, "mob", "Zombie", 1000);

        assertEquals("12 64 -3 in overworld, killed by Zombie", death.describe());
    }

    @Test
    void fallsBackToTheDamageCauseWithoutAnEntity() {
        DeathRecord death = new DeathRecord("the_nether", 4, 31, 8, "lava", "", 2000);

        assertEquals("4 31 8 in the_nether, died to lava", death.describe());
    }

    @Test
    void toleratesAnEmptyRecord() {
        assertEquals("0 0 0", new DeathRecord(null, 0, 0, 0, null, null, 0).describe());
    }
}
