package dev.friendai.perception;

/** A top-down ASCII map of an area, with no Minecraft types. */
public final class AreaMap {
    private AreaMap() {}

    /** One character per column, asked for by offset from the centre: dx west(-) to east(+), dz north(-) to south(+). */
    public interface Glyphs {
        char glyphAt(int dx, int dz);
    }

    /** Rows run north to south, each west to east, separated by a newline. */
    public static String render(int radius, Glyphs glyphs) {
        StringBuilder map = new StringBuilder();
        for (int dz = -radius; dz <= radius; dz++) {
            if (dz > -radius) {
                map.append('\n');
            }
            for (int dx = -radius; dx <= radius; dx++) {
                map.append(glyphs.glyphAt(dx, dz));
            }
        }
        return map.toString();
    }
}
