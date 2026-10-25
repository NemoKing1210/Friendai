package dev.friendai.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Colours and small drawing helpers shared by the companion menu. */
final class Ui {
    static final int TEXT = 0xFFE8EDF2;
    static final int DIM = 0xFF8B98A5;
    static final int ACCENT = 0xFF4FC3F7;
    static final int GOOD = 0xFF55D68A;
    static final int WARN = 0xFFF2B84B;
    static final int BAD = 0xFFE5534B;
    static final int PURPLE = 0xFFB794F6;
    static final int PANEL = 0xD0121820;
    static final int CARD = 0xB01D2630;
    static final int CARD_HOVER = 0xC02A3647;
    static final int BORDER = 0xFF2E3A47;
    static final int TRACK = 0xFF0B0F14;

    /** Fixed colours for things that come in a handful of kinds (fact categories, log kinds). */
    static final int[] PALETTE = {ACCENT, GOOD, WARN, PURPLE, 0xFFFF8FA3, 0xFF7FD6D6, 0xFFC0A080};

    static final int BADGE_HEIGHT = 12;

    private Ui() {}

    static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
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

    /** A tinted pill with the text inside; returns its width so callers can lay out the next one. */
    static int badge(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        int w = badgeWidth(font, text);
        g.fill(x, y, x + w, y + BADGE_HEIGHT, alpha(color, 0x38));
        g.fill(x, y, x + 1, y + BADGE_HEIGHT, color);
        g.text(font, text, x + 5, y + 2, color);
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

    /** A section caption: a small dim label with a rule to its right. */
    static void heading(GuiGraphicsExtractor g, Font font, Component label, int x, int y, int w) {
        g.text(font, label, x, y, DIM);
        int lineX = x + font.width(label) + 6;
        if (lineX < x + w) {
            g.fill(lineX, y + font.lineHeight / 2, x + w, y + font.lineHeight / 2 + 1, BORDER);
        }
    }
}
