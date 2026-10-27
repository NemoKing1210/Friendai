package dev.friendai.client.ui;

/**
 * The companion menu's one palette and one widget set, borrowed from the game itself: the vanilla window bevel,
 * the vanilla button sprites, a light face with dark labels and a dark list surface for long text, like the
 * server list. Status hues are vanilla's own colours darkened just enough to stay readable on the light face.
 *
 * <p>Pure arithmetic and constants only: no drawing, no Minecraft types. {@link Ui} draws with these; the tests
 * pin the relationships (contrast, distinctness, which sprite a state uses), never a colour literal.
 */
final class MenuTheme {
    /* The vanilla container window, the same numbers CompanionInventoryScreen draws with. */
    static final int WINDOW_FACE = 0xFFC6C6C6;
    static final int WINDOW_HIGHLIGHT = 0xFFFFFFFF;
    static final int WINDOW_SHADOW = 0xFF555555;
    static final int WINDOW_OUTLINE = 0xFF000000;

    /** The screen title sits on the dimmed world above the window, so it is vanilla's white-with-shadow text. */
    static final int TITLE = 0xFFFFFFFF;

    /** Vanilla container labels are dark grey; secondary text is its grey, darkened to stay above 4.5:1. */
    static final int TEXT = 0xFF404040;
    static final int DIM = 0xFF4E4E4E;

    /* Status hues: vanilla's accent colours, darkened until each one is readable on the face. */
    static final int ACCENT = 0xFF1E438B;
    static final int GOOD = 0xFF135A26;
    static final int WARN = 0xFF6B4400;
    static final int BAD = 0xFF9B1B18;
    static final int PURPLE = 0xFF5B2E8C;

    /** A dark list surface, like the vanilla server list, for long running text. */
    static final int WELL = 0xFF1A1A1A;
    static final int ON_WELL = 0xFFFFFFFF;
    static final int ON_WELL_DIM = 0xFFA0A0A0;
    static final int ON_WELL_BAD = 0xFFFF8A80;

    /** Rows sit on the face; the hover wash is the vanilla white overlay. */
    static final int CARD = WINDOW_FACE;
    static final int CARD_HOVER = 0x66FFFFFF;

    static final int BORDER = WINDOW_SHADOW;
    static final int TRACK = 0xFFE0E0E0;

    /** Fixed colours for things that come in a handful of kinds (fact categories, log kinds). */
    static final int[] PALETTE = {ACCENT, GOOD, WARN, PURPLE, BAD, 0xFF155654, 0xFF6B4A1F};

    static final int BADGE_HEIGHT = 12;

    /** The game's widget sprites; one button style, chosen by state. */
    static final String SPRITE_BUTTON = "widget/button";
    static final String SPRITE_BUTTON_HIGHLIGHTED = "widget/button_highlighted";
    static final String SPRITE_BUTTON_DISABLED = "widget/button_disabled";

    private MenuTheme() {}

    static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** {@code color} composited over {@code background}, so a translucent wash can be measured as a real pixel. */
    static int over(int color, int background) {
        int a = (color >>> 24) & 0xFF;
        int r = blend((color >> 16) & 0xFF, (background >> 16) & 0xFF, a);
        int g = blend((color >> 8) & 0xFF, (background >> 8) & 0xFF, a);
        int b = blend(color & 0xFF, background & 0xFF, a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int blend(int top, int bottom, int alpha) {
        return (top * alpha + bottom * (255 - alpha)) / 255;
    }

    /** WCAG relative luminance of an opaque colour (alpha is ignored). */
    static double luminance(int color) {
        double r = linear((color >> 16) & 0xFF);
        double g = linear((color >> 8) & 0xFF);
        double b = linear(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio between two opaque colours, from 1:1 to 21:1. */
    static double contrast(int a, int b) {
        double high = Math.max(luminance(a), luminance(b));
        double low = Math.min(luminance(a), luminance(b));
        return (high + 0.05) / (low + 0.05);
    }

    static boolean readable(int foreground, int background, double minimum) {
        return contrast(foreground, background) >= minimum;
    }

    /** Which vanilla sprite a button uses: disabled, selected/focused (highlighted) or plain. */
    static String buttonSprite(boolean enabled, boolean selected) {
        if (!enabled) {
            return SPRITE_BUTTON_DISABLED;
        }
        return selected ? SPRITE_BUTTON_HIGHLIGHTED : SPRITE_BUTTON;
    }
}
