package dev.friendai.build;

import java.util.List;

/**
 * A structure to build. Coordinates are relative to the minimum corner: x west to east, y bottom to top, z north
 * to south. Positions that are not listed are left unchanged.
 */
public record Blueprint(String name, int width, int height, int depth, List<BlockSpec> blocks) {
    public static final int MAX_SIZE = 32;
    public static final int MAX_BLOCKS = 8192;

    public Blueprint {
        blocks = List.copyOf(blocks);
    }
}
