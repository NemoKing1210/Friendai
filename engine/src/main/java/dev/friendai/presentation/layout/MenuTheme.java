package dev.friendai.presentation.layout;

import dev.friendai.presentation.UiContrast;

/**
 * The companion menu's one palette and one widget set, borrowed from the game itself: the vanilla window bevel,
 * the vanilla button sprites, a light face with dark labels and a dark list surface for long text, like the
 * server list. Status hues are vanilla's own colours darkened just enough to stay readable on the light face.
 *
 * <p>Pure arithmetic and constants only: no drawing or Minecraft types. Client rendering consumes this palette
 * while keeping the game's own widget sprites, so the theme never duplicates a sprite name that could drift from
 * vanilla. The tests pin the relationships (contrast, distinctness), never a colour literal.
 */
public final class MenuTheme {
    /* The vanilla container window palette used by client screens. */
    public static final int WINDOW_FACE = 0xFFC6C6C6;
    public static final int WINDOW_HIGHLIGHT = 0xFFFFFFFF;
    public static final int WINDOW_SHADOW = 0xFF555555;
    public static final int WINDOW_OUTLINE = 0xFF000000;

    /** The screen title sits on the dimmed world above the window, so it is vanilla's white-with-shadow text. */
    public static final int TITLE = 0xFFFFFFFF;

    /** Vanilla container labels are dark grey; secondary text is its grey, darkened to stay above 4.5:1. */
    public static final int TEXT = 0xFF404040;

    public static final int DIM = 0xFF4E4E4E;

    /* Status hues: vanilla's accent colours, darkened until each one is readable on the face. */
    public static final int ACCENT = 0xFF1E438B;
    public static final int GOOD = 0xFF135A26;
    public static final int WARN = 0xFF6B4400;
    public static final int BAD = 0xFF9B1B18;
    public static final int PURPLE = 0xFF5B2E8C;

    /** A dark list surface, like the vanilla server list, for long running text. */
    public static final int WELL = 0xFF1A1A1A;

    public static final int ON_WELL = 0xFFFFFFFF;
    public static final int ON_WELL_DIM = 0xFFA0A0A0;
    public static final int ON_WELL_BAD = 0xFFFF8A80;

    /** Rows sit on the face; the hover wash is the vanilla white overlay. */
    public static final int CARD = WINDOW_FACE;

    public static final int CARD_HOVER = 0x66FFFFFF;

    public static final int BORDER = WINDOW_SHADOW;
    public static final int TRACK = 0xFFE0E0E0;

    /** Fixed colours for things that come in a handful of kinds (fact categories, log kinds). */
    public static final int[] PALETTE = {ACCENT, GOOD, WARN, PURPLE, BAD, 0xFF155654, 0xFF6B4A1F};

    /**
     * The three position-key hues on a task chip's position ("X 12 Y 64 Z -8"). Measured on the chip's own row
     * wash, the lightest surface they are drawn on: X 4.62:1, Y 4.56:1, Z 4.74:1. Y was 0xFF2F7D32 at 3.74:1.
     */
    public static final int AXIS_X = 0xFFB03030;

    public static final int AXIS_Y = 0xFF296E2C;
    public static final int AXIS_Z = 0xFF2A5DA8;

    /** Vanilla's experience-bar green, kept for the bar itself: 5.77:1 on {@link #WINDOW_SHADOW}. */
    public static final int XP_BAR = 0xFF80FF20;

    /** The same green darkened for the level label, which sits on the light face: 4.64:1 there. */
    public static final int XP_TEXT = 0xFF2E5C0C;

    /**
     * An off-state indicator dot on a vanilla button sprite, whose face is a mid grey (#6F6F6F): 3.26:1 on that
     * face and 9.05:1 on the disabled sprite's (#2C2C2C). {@link #DIM} is a text colour and vanishes there at 1.6:1.
     */
    public static final int WIDGET_DOT = 0xFFD0D0D0;

    public static final int BADGE_HEIGHT = 12;

    private MenuTheme() {}

    public static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** {@code color} composited over {@code background}, so a translucent wash can be measured as a real pixel. */
    public static int over(int color, int background) {
        return UiContrast.over(color, background);
    }

    /** WCAG relative luminance of an opaque colour (alpha is ignored). */
    public static double luminance(int color) {
        return UiContrast.luminance(color);
    }

    /** WCAG contrast ratio between two opaque colours, from 1:1 to 21:1. */
    public static double contrast(int a, int b) {
        return UiContrast.contrast(a, b);
    }

    public static boolean readable(int foreground, int background, double minimum) {
        return UiContrast.readable(foreground, background, minimum);
    }
}
