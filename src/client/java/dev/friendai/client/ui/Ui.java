package dev.friendai.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Colours and small drawing helpers shared by the companion menu, all taken from {@link MenuTheme}: a vanilla
 * window with its bevel, vanilla container labels in dark grey, status chips in white-on-colour, and a dark list
 * surface for long running text. Nothing here invents a colour; a new role is added to the theme and measured
 * there.
 */
final class Ui {
    static final int TEXT = MenuTheme.TEXT;
    static final int DIM = MenuTheme.DIM;
    static final int ACCENT = MenuTheme.ACCENT;
    static final int GOOD = MenuTheme.GOOD;
    static final int WARN = MenuTheme.WARN;
    static final int BAD = MenuTheme.BAD;
    static final int PURPLE = MenuTheme.PURPLE;

    static final int PANEL = MenuTheme.WINDOW_FACE;
    static final int CARD = MenuTheme.CARD;
    static final int CARD_HOVER = MenuTheme.CARD_HOVER;
    static final int BORDER = MenuTheme.BORDER;
    static final int TRACK = MenuTheme.TRACK;

    /** The dark list surface and its text, for running logs and long bodies. */
    static final int WELL = MenuTheme.WELL;
    static final int ON_WELL = MenuTheme.ON_WELL;
    static final int ON_WELL_DIM = MenuTheme.ON_WELL_DIM;
    static final int ON_WELL_BAD = MenuTheme.ON_WELL_BAD;

    static final int[] PALETTE = MenuTheme.PALETTE;

    static final int BADGE_HEIGHT = MenuTheme.BADGE_HEIGHT;

    private Ui() {}

    static int alpha(int color, int alpha) {
        return MenuTheme.alpha(color, alpha);
    }

    static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** The vanilla window: black outline, light top-left bevel, grey face, dark bottom-right shadow. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 6 || h < 6) {
            g.fill(x, y, x + w, y + h, PANEL);
            border(g, x, y, w, h, MenuTheme.WINDOW_OUTLINE);
            return;
        }
        g.fill(x, y, x + w, y + h, MenuTheme.WINDOW_OUTLINE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, MenuTheme.WINDOW_HIGHLIGHT);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, PANEL);
        g.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, MenuTheme.WINDOW_SHADOW);
        g.fill(x + w - 3, y + 2, x + w - 1, y + h - 3, MenuTheme.WINDOW_SHADOW);
    }

    /** A vanilla list surface: dark, flat, one border. Text on it uses {@link #ON_WELL}. */
    static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, WELL);
        border(g, x, y, w, h, BORDER);
    }

    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int edge) {
        g.fill(x, y, x + w, y + h, fill);
        border(g, x, y, w, h, edge);
    }

    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        card(g, x, y, w, h, CARD, BORDER);
    }

    /** A horizontal gauge filled to {@code fraction} (0..1). */
    static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float fraction, int color) {
        g.fill(x, y, x + w, y + h, TRACK);
        int filled = Math.round((w - 2) * Math.max(0f, Math.min(1f, fraction)));
        if (filled > 0) {
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, color);
            g.fill(x + 1, y + 1, x + 1 + filled, y + 2, alpha(0xFFFFFF, 0x30));
        }
        border(g, x, y, w, h, BORDER);
    }

    /** A status chip: an opaque status-coloured pill with white text, like a vanilla tooltip. */
    static int badge(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        int w = badgeWidth(font, text);
        g.fill(x, y, x + w, y + BADGE_HEIGHT, color);
        text(g, font, text, x + 5, y + 2, ON_WELL);
        return w;
    }

    static int badgeWidth(Font font, String text) {
        return font.width(text) + 8;
    }

    static void dot(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + size, color);
    }

    /** The text cut to {@code width} pixels with a trailing "..." when it does not fit. */
    static String clip(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    /** A section caption: a dark label with a rule to its right. */
    static void heading(GuiGraphicsExtractor g, Font font, Component label, int x, int y, int w) {
        text(g, font, label, x, y, TEXT);
        int lineX = x + font.width(label) + 6;
        if (lineX < x + w) {
            g.fill(lineX, y + font.lineHeight / 2, x + w, y + font.lineHeight / 2 + 1, BORDER);
        }
    }

    /** Text with the game's own shadow rule: light text casts a shadow, dark text on the face does not. */
    static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.text(font, text, x, y, color, shadow(color));
    }

    static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.text(font, text, x, y, color, shadow(color));
    }

    static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, int x, int y, int color) {
        g.text(font, text, x, y, color, shadow(color));
    }

    private static boolean shadow(int color) {
        return MenuTheme.luminance(color) > 0.5;
    }
}
