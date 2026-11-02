package dev.friendai.presentation;

/**
 * WCAG 2.2 contrast arithmetic over {@code 0xRRGGBB} colours: the numbers the menu's palette is measured with.
 * Relative luminance and the contrast ratio come from WCAG 2.2 SC 1.4.3 and 1.4.11, and {@link #over} composites a
 * translucent wash so a pair is measured on the pixels that actually reach the screen rather than on the wash's own
 * colour.
 *
 * <p>Pure arithmetic and no Minecraft types, so a screen and a test compute the same ratio for the same pair. The
 * palette itself lives in {@code MenuTheme}, which delegates here.
 */
public final class UiContrast {
    private UiContrast() {}

    /** {@code color} composited over {@code background} at its own alpha: the pixel a wash leaves behind. */
    public static int over(int color, int background) {
        int alpha = (color >>> 24) & 0xFF;
        return 0xFF000000
                | blend((color >> 16) & 0xFF, (background >> 16) & 0xFF, alpha) << 16
                | blend((color >> 8) & 0xFF, (background >> 8) & 0xFF, alpha) << 8
                | blend(color & 0xFF, background & 0xFF, alpha);
    }

    private static int blend(int top, int bottom, int alpha) {
        return (top * alpha + bottom * (255 - alpha)) / 255;
    }

    /** WCAG relative luminance of an opaque colour (alpha is ignored): 0 for black, 1 for white. */
    public static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 0xFF)
                + 0.7152 * linear((color >> 8) & 0xFF)
                + 0.0722 * linear(color & 0xFF);
    }

    /** WCAG contrast ratio of two colours, from 1:1 to 21:1, independent of argument order. */
    public static double contrast(int a, int b) {
        double high = Math.max(luminance(a), luminance(b));
        double low = Math.min(luminance(a), luminance(b));
        return (high + 0.05) / (low + 0.05);
    }

    /** Whether {@code foreground} reaches {@code minimum}:1 against {@code background}. */
    public static boolean readable(int foreground, int background, double minimum) {
        return contrast(foreground, background) >= minimum;
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
