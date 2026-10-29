package dev.friendai.agent.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.friendai.agent.perception.OwnerFocus.Visibility;
import org.junit.jupiter.api.Test;

class OwnerFocusTest {
    @Test
    void visibleListsPositionSupportTargetAndItem() {
        OwnerFocus focus = OwnerFocus.visible(
                "Owner",
                6.2,
                "10 64 -4",
                "grass_block at 10 63 -4",
                "block oak_log at 12 65 -3 (5.1 blocks)",
                "iron_pickaxe",
                "behind-right",
                "looking away from you",
                20f,
                20f,
                20,
                0,
                "ok");
        assertEquals(
                "Owner: 6.2 blocks away, in view (behind-right of you), looking away from you\n"
                        + "  at: 10 64 -4\n"
                        + "  standing on: grass_block at 10 63 -4\n"
                        + "  looking at: block oak_log at 12 65 -3 (5.1 blocks)\n"
                        + "  holding: iron_pickaxe\n"
                        + "  health: 20/20, food 20/20, armor 0\n"
                        + "  body: ok",
                focus.toPromptText());
    }

    @Test
    void outOfSightAdmitsItCannotTell() {
        OwnerFocus focus = OwnerFocus.unavailable("Speaker (Alex)", Visibility.OUT_OF_SIGHT, 41.3);
        assertEquals(
                "Speaker (Alex): 41.3 blocks away, out of sight (I can't tell where they stand or look)",
                focus.toPromptText());
        assertFalse(focus.toPromptText().contains("null"), focus.toPromptText());
    }

    @Test
    void otherDimension() {
        OwnerFocus focus = OwnerFocus.unavailable("Owner", Visibility.OTHER_DIMENSION, 0);
        assertEquals("Owner: in another dimension", focus.toPromptText());
        assertFalse(focus.toPromptText().contains("null"), focus.toPromptText());
    }

    @Test
    void offline() {
        OwnerFocus focus = OwnerFocus.unavailable("Owner", Visibility.OFFLINE, 0);
        assertEquals("Owner: offline", focus.toPromptText());
        assertFalse(focus.toPromptText().contains("null"), focus.toPromptText());
    }

    @Test
    void distanceIsLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            OwnerFocus focus = OwnerFocus.unavailable("Owner", Visibility.OUT_OF_SIGHT, 3.5);
            assertEquals(
                    "Owner: 3.5 blocks away, out of sight (I can't tell where they stand or look)",
                    focus.toPromptText());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
