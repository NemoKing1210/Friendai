package dev.friendai.chat;

/**
 * Derives a stable colour from a companion name, so renaming a companion changes its colour in chat and above
 * its head without storing anything. Pure Java: the Minecraft layer turns the value into a text style.
 *
 * <p>The hue comes from an FNV-1a hash (stable across JVMs and releases, unlike {@code Object} hashes), and the
 * saturation and brightness are fixed so every hue stays readable on the dark chat background.
 */
public final class NameColor {
    /** Shown for a blank name; a normal name always produces a colour. */
    public static final int DEFAULT = 0xFFFFFF;

    private static final float SATURATION = 0.6f;
    private static final float BRIGHTNESS = 1f;
    private static final int HUE_STEPS = 360;

    private NameColor() {}

    /** The {@code 0xRRGGBB} colour for {@code name}, or {@link #DEFAULT} when it is null or blank. */
    public static int rgb(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        return rgbForHue(hue(name));
    }

    private static int hue(String name) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < name.length(); i++) {
            hash ^= name.charAt(i);
            hash *= 0x01000193;
        }
        return (int) (Integer.toUnsignedLong(hash) % HUE_STEPS);
    }

    private static int rgbForHue(int hue) {
        float chroma = BRIGHTNESS * SATURATION;
        float second = chroma * (1f - Math.abs((hue / 60f) % 2f - 1f));
        float floor = BRIGHTNESS - chroma;
        float red;
        float green;
        float blue;
        if (hue < 60) {
            red = chroma;
            green = second;
            blue = 0f;
        } else if (hue < 120) {
            red = second;
            green = chroma;
            blue = 0f;
        } else if (hue < 180) {
            red = 0f;
            green = chroma;
            blue = second;
        } else if (hue < 240) {
            red = 0f;
            green = second;
            blue = chroma;
        } else if (hue < 300) {
            red = second;
            green = 0f;
            blue = chroma;
        } else {
            red = chroma;
            green = 0f;
            blue = second;
        }
        return channel(red + floor) << 16 | channel(green + floor) << 8 | channel(blue + floor);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }
}
