package dev.friendai.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.presentation.layout.MenuTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Every text colour and state indicator the companion menu draws, measured against the surface it is drawn on.
 *
 * <p>Thresholds are WCAG 2.2, never a colour literal: <b>4.5:1</b> for body-size text (SC 1.4.3 at level AA), and
 * <b>3.0:1</b> for large text (SC 1.4.3 treats text from 18pt as large; the one label here is drawn at twice the
 * body font) and for non-text state indicators (SC 1.4.11: activity bars, indicator dots).
 *
 * <p>Each entry names its draw site, so a palette change can be traced to the pixels it moves. Surfaces that are
 * not palette constants are measured where they come from: a translucent wash is composited with
 * {@link UiContrast#over} instead of being measured as its own colour, and the vanilla button faces were sampled
 * from the 26.3 client's nine-slice sprites ({@code assets/minecraft/textures/gui/sprites/widget/button.png}
 * face {@code #6F6F6F}, {@code button_disabled.png} face {@code #2C2C2C}). Vanilla's own disabled label colour is
 * the constant {@code -6250336} in {@code AbstractWidget.WithInactiveMessage.defaultInactiveMessage}.
 */
class UiContrastTest {
    private static final double BODY = 4.5;
    private static final double LARGE = 3.0;

    /** The card fill every tab's body text sits on. */
    private static final int ROW = MenuTheme.CARD;

    /** A selected or hovered row: the white wash composited onto the card, the lightest surface text lands on. */
    private static final int ROW_HOVER = UiContrast.over(MenuTheme.CARD_HOVER, MenuTheme.CARD);

    /** LogTab's selected-line highlight and its even-row wash, both over the well. */
    private static final int LOG_SELECTION = UiContrast.over(MenuTheme.alpha(MenuTheme.ACCENT, 0x70), MenuTheme.WELL);

    private static final int LOG_ZEBRA = UiContrast.over(MenuTheme.alpha(0xFFFFFF, 0x08), MenuTheme.WELL);

    /** Vanilla's button sprite faces, as sampled from the sprites named above. */
    private static final int BUTTON_FACE = 0xFF6F6F6F;

    private static final int BUTTON_FACE_DISABLED = 0xFF2C2C2C;

    private static final int VANILLA_DISABLED_LABEL = 0xFFA0A0A0;

    private record Pair(String site, int ink, int paper, double minimum) {}

    @Test
    void theHelperMatchesTheWcagReferenceNumbers() {
        assertEquals(0.0, UiContrast.luminance(0xFF000000), 0.0001);
        assertEquals(1.0, UiContrast.luminance(0xFFFFFFFF), 0.0001);
        assertEquals(21.0, UiContrast.contrast(0xFF000000, 0xFFFFFFFF), 0.01, "black on white is 21:1");
        // WCAG's own worked example: #777777 on white is 4.48:1.
        assertEquals(4.48, UiContrast.contrast(0xFF777777, 0xFFFFFFFF), 0.01);
        assertEquals(1.0, UiContrast.contrast(MenuTheme.TEXT, MenuTheme.TEXT), 0.0, "a colour on itself");
        assertEquals(
                UiContrast.contrast(MenuTheme.BAD, MenuTheme.CARD),
                UiContrast.contrast(MenuTheme.CARD, MenuTheme.BAD),
                0.0,
                "the ratio is order-independent");
        assertEquals(0xFFC6C6C6, UiContrast.over(MenuTheme.WINDOW_FACE, 0xFF000000), "an opaque wash replaces it");
        assertEquals(0xFF000000, UiContrast.over(0x00FFFFFF, 0xFF000000), "a clear wash leaves it");
        assertEquals(0xFFDCDCDC, UiContrast.over(MenuTheme.CARD_HOVER, MenuTheme.WINDOW_FACE), "the hover row");
        assertTrue(UiContrast.readable(MenuTheme.TEXT, MenuTheme.CARD, BODY));
    }

    @Test
    void everyPairTheMenuDrawsIsReadable() {
        for (Pair pair : pairs()) {
            double ratio = UiContrast.contrast(pair.ink(), pair.paper());
            assertTrue(
                    ratio >= pair.minimum(),
                    () -> pair.site() + ": " + hex(pair.ink()) + " on " + hex(pair.paper()) + " is "
                            + String.format(Locale.ROOT, "%.2f", ratio) + ":1, needs " + pair.minimum() + ":1");
        }
    }

    private static List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        // Body text and labels on the light face, drawn by Ui.text through every tab.
        pairs.add(new Pair("Ui heading caption on the panel", MenuTheme.TEXT, MenuTheme.WINDOW_FACE, BODY));
        pairs.add(new Pair("body text on a card", MenuTheme.TEXT, ROW, BODY));
        pairs.add(new Pair("body text on a selected or hovered row", MenuTheme.TEXT, ROW_HOVER, BODY));
        pairs.add(new Pair("dim text on a card", MenuTheme.DIM, ROW, BODY));
        pairs.add(new Pair("dim text on a selected or hovered row", MenuTheme.DIM, ROW_HOVER, BODY));
        pairs.add(new Pair("LogTab off chip label", MenuTheme.DIM, ROW, BODY));
        pairs.add(new Pair("empty-list label", MenuTheme.DIM, MenuTheme.WINDOW_FACE, BODY));
        pairs.add(new Pair("Task plan status, accent", MenuTheme.ACCENT, ROW, BODY));
        pairs.add(new Pair("status ok label", MenuTheme.GOOD, ROW, BODY));
        pairs.add(new Pair("blocked or full warning label", MenuTheme.WARN, ROW, BODY));
        pairs.add(new Pair("failed or disabled error label", MenuTheme.BAD, ROW, BODY));
        pairs.add(new Pair("creative-mode label", MenuTheme.PURPLE, ROW, BODY));
        // TaskChips draws the coordinates as three coloured keys on a chip filled with the row wash.
        pairs.add(new Pair("task chip X key", MenuTheme.AXIS_X, ROW_HOVER, BODY));
        pairs.add(new Pair("task chip Y key", MenuTheme.AXIS_Y, ROW_HOVER, BODY));
        pairs.add(new Pair("task chip Z key", MenuTheme.AXIS_Z, ROW_HOVER, BODY));
        // The dark well: running logs, perception bodies and the feeling list.
        pairs.add(new Pair("well body text", MenuTheme.ON_WELL, MenuTheme.WELL, BODY));
        pairs.add(new Pair("well timestamp", MenuTheme.ON_WELL_DIM, MenuTheme.WELL, BODY));
        pairs.add(new Pair("well error line", MenuTheme.ON_WELL_BAD, MenuTheme.WELL, BODY));
        pairs.add(new Pair("error line on the even-row wash", MenuTheme.ON_WELL_BAD, LOG_ZEBRA, BODY));
        pairs.add(new Pair("selected line on the selection", MenuTheme.ON_WELL, LOG_SELECTION, BODY));
        pairs.add(new Pair("selected error line on the selection", MenuTheme.ON_WELL_BAD, LOG_SELECTION, BODY));
        // Status pills: Ui.badge puts white on the status colour, both solid and as a hovered log chip.
        for (int color : MenuTheme.PALETTE) {
            pairs.add(new Pair("Ui.badge label on " + hex(color), MenuTheme.ON_WELL, color, BODY));
            pairs.add(new Pair("hovered log chip " + hex(color), MenuTheme.ON_WELL, chip(color), BODY));
        }
        pairs.add(new Pair("offline badge label", MenuTheme.ON_WELL, MenuTheme.DIM, BODY));
        // Non-text state indicators: activity bars on their track, the XP bar on its own track.
        pairs.add(new Pair("health bar", MenuTheme.BAD, MenuTheme.TRACK, LARGE));
        pairs.add(new Pair("hunger bar", MenuTheme.WARN, MenuTheme.TRACK, LARGE));
        pairs.add(new Pair("inventory bar", MenuTheme.ACCENT, MenuTheme.TRACK, LARGE));
        pairs.add(new Pair("experience bar", MenuTheme.GOOD, MenuTheme.TRACK, LARGE));
        pairs.add(new Pair("companion XP bar on its track", MenuTheme.XP_BAR, MenuTheme.WINDOW_SHADOW, LARGE));
        // The label at twice the body size: the companion's initial on its own state tint.
        pairs.add(new Pair("idle monogram", MenuTheme.GOOD, tint(MenuTheme.GOOD), LARGE));
        pairs.add(new Pair("thinking monogram", MenuTheme.WARN, tint(MenuTheme.WARN), LARGE));
        pairs.add(new Pair("offline monogram", MenuTheme.DIM, tint(MenuTheme.DIM), LARGE));
        // Vanilla paints the button; our own ink is the off dot and the XP level label.
        pairs.add(new Pair("FlatButton off dot", MenuTheme.WIDGET_DOT, BUTTON_FACE, LARGE));
        pairs.add(new Pair("FlatButton off dot, disabled button", MenuTheme.WIDGET_DOT, BUTTON_FACE_DISABLED, LARGE));
        pairs.add(new Pair("vanilla disabled button label", VANILLA_DISABLED_LABEL, BUTTON_FACE_DISABLED, BODY));
        pairs.add(new Pair("companion XP level label", MenuTheme.XP_TEXT, MenuTheme.WINDOW_FACE, BODY));
        return pairs;
    }

    /** The fill of a hovered log kind chip: its status colour at 0xE0 over the panel, as LogTab draws it. */
    private static int chip(int color) {
        return UiContrast.over(MenuTheme.alpha(color, 0xE0), MenuTheme.CARD);
    }

    /** The 36x36 avatar box: the state colour at 0x30 over the card, as ControlTab draws it. */
    private static int tint(int color) {
        return UiContrast.over(MenuTheme.alpha(color, 0x30), MenuTheme.CARD);
    }

    private static String hex(int color) {
        return String.format("%08X", color);
    }
}
