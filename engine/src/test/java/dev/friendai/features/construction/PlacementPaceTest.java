package dev.friendai.features.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlacementPaceTest {
    @Test
    void aFreshPaceIsReady() {
        PlacementPace pace = new PlacementPace();

        assertTrue(pace.ready());
        assertEquals(0, pace.ticksLeft());
    }

    @Test
    void onePlacementWaitsExactlyTheIntervalBeforeTheNextOne() {
        PlacementPace pace = new PlacementPace();
        pace.spent(4);

        for (int elapsed = 1; elapsed < 4; elapsed++) {
            pace.tick();
            assertFalse(pace.ready(), "the pace must still be waiting after " + elapsed + " ticks");
        }
        pace.tick();
        assertTrue(pace.ready(), "the pace must be ready on the fourth tick");
    }

    @Test
    void thePlayerDefaultOfFourTicksCapsASurvivalPrinterAtFiveBlocksASecond() {
        PlacementPace pace = new PlacementPace();
        int placements = 0;
        for (int tick = 0; tick < 100; tick++) {
            pace.tick();
            if (pace.ready()) {
                pace.spent(PlacementPace.DEFAULT_INTERVAL);
                placements++;
            }
        }

        assertEquals(25, placements, "100 ticks at one block per 4 ticks");
    }

    @Test
    void intervalOneIsOneBlockPerTick() {
        PlacementPace pace = new PlacementPace();
        int placements = 0;
        for (int tick = 0; tick < 20; tick++) {
            pace.tick();
            if (pace.ready()) {
                pace.spent(1);
                placements++;
            }
        }

        assertEquals(20, placements);
    }

    @Test
    void theIntervalIsClampedAtBothEnds() {
        assertEquals(1, PlacementPace.clamp(0));
        assertEquals(1, PlacementPace.clamp(-5));
        assertEquals(40, PlacementPace.clamp(41));
        assertEquals(40, PlacementPace.clamp(1_000));
        assertEquals(9, PlacementPace.clamp(9));

        PlacementPace pace = new PlacementPace();
        pace.spent(0);
        assertEquals(1, pace.ticksLeft());
        pace.spent(999);
        assertEquals(40, pace.ticksLeft());
    }

    @Test
    void creativeIsAlwaysAllowedEveryTickWhateverTheSetting() {
        assertEquals(1, PlacementPace.interval(40, true));
        assertEquals(4, PlacementPace.interval(4, false));
        assertEquals(40, PlacementPace.interval(999, false));
        assertEquals(1, PlacementPace.interval(0, false));
    }

    @Test
    void resetClearsTheWaitSoAStoppedJobDelaysNothing() {
        PlacementPace pace = new PlacementPace();
        pace.spent(40);

        pace.reset();

        assertTrue(pace.ready());
        assertEquals(0, pace.ticksLeft());
    }
}
