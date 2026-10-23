package dev.friendai.test;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

final class GameTestSupport {
    private GameTestSupport() {}

    /** A mock owner standing at the given test-relative position. */
    @SuppressWarnings("removal")
    static net.minecraft.server.level.ServerPlayer mockPlayerAt(GameTestHelper h, double x, double y, double z) {
        var player = h.makeMockServerPlayerInLevel();
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(x, y, z));
        player.teleportTo(pos.x, pos.y, pos.z);
        return player;
    }

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

    /** A stone floor at {@code y} with clear air above it, for tests that need a world band of their own. */
    static void platformAt(GameTestHelper h, int y, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, y, z, Blocks.STONE);
                for (int level = y + 1; level <= y + 6; level++) {
                    h.setBlock(x, level, z, Blocks.AIR);
                }
            }
        }
    }
}
