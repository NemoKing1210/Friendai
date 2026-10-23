package dev.friendai.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.perception.OwnerFocus.Visibility;
import org.junit.jupiter.api.Test;

class OwnerFocusTest {
    @Test
    void visibleListsPositionSupportTargetAndItem() {
        OwnerFocus focus = new OwnerFocus("Owner", Visibility.VISIBLE, 6.2, "10 64 -4",
                "grass_block at 10 63 -4", "block oak_log at 12 65 -3 (5.1 blocks)", "iron_pickaxe");
        assertEquals("Owner: 6.2 blocks away, in view\n"
                + "  at: 10 64 -4\n"
                + "  standing on: grass_block at 10 63 -4\n"
                + "  looking at: block oak_log at 12 65 -3 (5.1 blocks)\n"
                + "  holding: iron_pickaxe", focus.toPromptText());
    }

    @Test
    void outOfSightAdmitsItCannotTell() {
        OwnerFocus focus = new OwnerFocus("Speaker (Alex)", Visibility.OUT_OF_SIGHT, 41.3, null, null, null, null);
        assertEquals("Speaker (Alex): 41.3 blocks away, out of sight (I can't tell where they stand or look)",
                focus.toPromptText());
    }

    @Test
    void otherDimension() {
        OwnerFocus focus = new OwnerFocus("Owner", Visibility.OTHER_DIMENSION, 0, null, null, null, null);
        assertEquals("Owner: in another dimension", focus.toPromptText());
    }

    @Test
    void offline() {
        OwnerFocus focus = new OwnerFocus("Owner", Visibility.OFFLINE, 0, null, null, null, null);
        assertEquals("Owner: offline", focus.toPromptText());
    }

    @Test
    void distanceIsLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            OwnerFocus focus = new OwnerFocus("Owner", Visibility.OUT_OF_SIGHT, 3.5, null, null, null, null);
            assertEquals("Owner: 3.5 blocks away, out of sight (I can't tell where they stand or look)",
                    focus.toPromptText());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
