package dev.friendai.features.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NameColorTest {
    @Test
    void sameNameAlwaysGetsTheSameColour() {
        assertEquals(NameColor.rgb("Nova"), NameColor.rgb("Nova"));
    }

    @Test
    void knownNamesKeepTheirColourAcrossReleases() {
        // Changing these means every existing companion changes colour, so pin the mapping.
        assertEquals(0xB566FF, NameColor.rgb("Nova"));
        assertEquals(0xFF8566, NameColor.rgb("Ember"));
        assertEquals(0xFFC966, NameColor.rgb("Friendai"));
    }

    @Test
    void blankNameFallsBackToDefault() {
        assertEquals(NameColor.DEFAULT, NameColor.rgb(null));
        assertEquals(NameColor.DEFAULT, NameColor.rgb(""));
        assertEquals(NameColor.DEFAULT, NameColor.rgb("   "));
    }

    @Test
    void renamingChangesTheColour() {
        assertNotEquals(NameColor.rgb("Nova"), NameColor.rgb("Ember"));
        assertNotEquals(NameColor.rgb("Friendai"), NameColor.rgb("Companion"));
    }

    @Test
    void coloursAreBrightEnoughToReadInChat() {
        for (String name : List.of("Nova", "Ember", "Friendai", "x", "Companion", "Алекс")) {
            int rgb = NameColor.rgb(name);
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            int min = Math.min(red, Math.min(green, blue));
            int max = Math.max(red, Math.max(green, blue));
            assertTrue(min >= 100, name + " is too dark: #" + Integer.toHexString(rgb));
            assertEquals(255, max, name + " should keep one channel at full brightness");
        }
    }

    @Test
    void differentNamesGetDifferentColours() {
        List<String> names =
                List.of("Nova", "Ember", "Friendai", "x", "Companion", "Mira", "Kai", "Luna", "Rex", "Iris", "Bob");
        long distinct = names.stream().map(NameColor::rgb).distinct().count();
        assertEquals(names.size(), distinct, "expected every sample name to get its own colour");
    }
}
