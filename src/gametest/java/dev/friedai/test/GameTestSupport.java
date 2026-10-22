package dev.friedai.test;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

final class GameTestSupport {
    private GameTestSupport() {}

    /** A stone floor at y=1 with clear air above it, {@code size} blocks on each side. */
    static void platform(GameTestHelper h, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, 1, z, Blocks.STONE);
                for (int y = 2; y <= 6; y++) {
                    h.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
    }
}
