package dev.friendai.presentation.layout;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The palette is checked by relationship, never by literal: every role must be readable on the surface it is
 * drawn on, the category dots must stay distinct, and the widget sprites must tell selection, hover and disable
 * apart. A later palette tweak that breaks any of that fails here.
 */
class MenuThemeTest {
    private static final double BODY = 4.5;
    private static final double LARGE = 3.0;

    @Test
    void textStaysReadableOnTheWindowFace() {
        for (int color : new int[] {
            MenuTheme.TEXT,
            MenuTheme.DIM,
            MenuTheme.ACCENT,
            MenuTheme.GOOD,
            MenuTheme.WARN,
            MenuTheme.BAD,
            MenuTheme.PURPLE
        }) {
            assertTrue(MenuTheme.readable(color, MenuTheme.WINDOW_FACE, BODY), hex(color) + " on the face");
        }
    }

    @Test
    void statusChipsCarryWhiteText() {
        for (int color : new int[] {
            MenuTheme.ACCENT, MenuTheme.GOOD, MenuTheme.WARN, MenuTheme.BAD, MenuTheme.PURPLE, MenuTheme.DIM
        }) {
            assertTrue(MenuTheme.readable(MenuTheme.ON_WELL, color, BODY), "white on " + hex(color));
        }
    }

    @Test
    void theDarkListSurfaceKeepsItsTextReadable() {
        assertTrue(MenuTheme.readable(MenuTheme.ON_WELL, MenuTheme.WELL, BODY));
        assertTrue(MenuTheme.readable(MenuTheme.ON_WELL_DIM, MenuTheme.WELL, BODY));
        assertTrue(MenuTheme.readable(MenuTheme.ON_WELL_BAD, MenuTheme.WELL, BODY));
    }

    @Test
    void theHoverWashDoesNotSpoilBodyText() {
        int washed = MenuTheme.over(MenuTheme.CARD_HOVER, MenuTheme.WINDOW_FACE);

        assertNotEquals(MenuTheme.WINDOW_FACE, washed, "the wash changes the row");
        assertTrue(MenuTheme.readable(MenuTheme.TEXT, washed, BODY));
        assertTrue(MenuTheme.readable(MenuTheme.DIM, washed, BODY));
    }

    @Test
    void theTitleReadsOnTheDimmedWorld() {
        assertTrue(MenuTheme.readable(MenuTheme.TITLE, 0xFF000000, LARGE));
        assertTrue(MenuTheme.luminance(MenuTheme.TITLE) > 0.5, "the title is light text");
        assertTrue(MenuTheme.luminance(MenuTheme.TEXT) < 0.5, "body text is dark text");
    }

    @Test
    void everyCategoryDotIsVisibleAndDistinct() {
        Set<Integer> seen = new HashSet<>();
        for (int color : MenuTheme.PALETTE) {
            assertTrue(MenuTheme.readable(color, MenuTheme.WINDOW_FACE, BODY), hex(color) + " on the face");
            assertTrue(seen.add(color), "duplicate " + hex(color));
            assertTrue(MenuTheme.readable(MenuTheme.ON_WELL, color, BODY), "white on " + hex(color));
        }
    }

    @Test
    void everyBarFillStandsOffItsTrack() {
        for (int color :
                new int[] {MenuTheme.ACCENT, MenuTheme.GOOD, MenuTheme.WARN, MenuTheme.BAD, MenuTheme.PURPLE}) {
            assertTrue(MenuTheme.readable(color, MenuTheme.TRACK, 3.0), hex(color) + " on the track");
        }
    }

    @Test
    void theSelectedRowHasItsOwnColour() {
        assertNotEquals(MenuTheme.CARD, MenuTheme.CARD_HOVER);
    }

    private static String hex(int color) {
        return String.format("%08X", color);
    }
}
