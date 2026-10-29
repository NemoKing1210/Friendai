package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.perception.resource.ResourceSight;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

final class GameTestSupport {
    private GameTestSupport() {}

    static FriendaiServerState serverState(MinecraftServer server) {
        return ServerRuntimeRegistry.getOrCreate(server).state();
    }

    static void closeRuntime(MinecraftServer server) {
        ServerRuntimeRegistry.removeAndClose(server);
    }

    static ServerRuntime runtime(GameTestHelper h) {
        return ServerRuntimeRegistry.getOrCreate(h.getLevel().getServer());
    }

    /** A mock owner standing at the given test-relative position. */
    @SuppressWarnings("removal")
    static net.minecraft.server.level.ServerPlayer mockPlayerAt(GameTestHelper h, double x, double y, double z) {
        var player = h.makeMockServerPlayerInLevel();
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(x, y, z));
        player.teleportTo(pos.x, pos.y, pos.z);
        return player;
    }

    /**
     * Loads and force-loads the chunks over a test-relative box so the entities in it tick.
     *
     * <p>A GameTest ticks entities only in the chunk(s) its 8x8 structure covers. A companion or a mob that walks or
     * is knocked out of them stops ticking completely: it freezes mid-step, keeps its velocity and, for a mob, stays
     * invulnerable, so a fight against it never lands another hit. The arenas here are larger than 8 blocks, so every
     * helper that builds one has to say where its entities may go.
     */
    static void keepLoaded(GameTestHelper h, int minX, int minZ, int maxX, int maxZ) {
        ServerLevel level = h.getLevel();
        BlockPos first = h.absolutePos(new BlockPos(minX, 0, minZ));
        BlockPos last = h.absolutePos(new BlockPos(maxX, 0, maxZ));
        int minChunkX = Math.floorDiv(Math.min(first.getX(), last.getX()), 16);
        int minChunkZ = Math.floorDiv(Math.min(first.getZ(), last.getZ()), 16);
        int maxChunkX = Math.floorDiv(Math.max(first.getX(), last.getX()), 16);
        int maxChunkZ = Math.floorDiv(Math.max(first.getZ(), last.getZ()), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
                // Load it now: an entity spawned in the same tick must land in a chunk that already ticks.
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    /** A stone wall ring {@code height} blocks tall on the edges of a platform built at {@code y}. */
    static void enclose(GameTestHelper h, int y, int size, int height) {
        for (int level = y + 1; level <= y + height; level++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    if (x == 0 || z == 0 || x == size - 1 || z == size - 1) {
                        h.setBlock(x, level, z, Blocks.STONE);
                    }
                }
            }
        }
    }

    /**
     * A stone floor at y=1 with clear air above it, {@code size} blocks on each side. The clear band reaches y=8
     * because the GameTest framework caps the default arena with a barrier layer there; a tree that grows through it
     * would otherwise climb into a wall.
     */
    static void platform(GameTestHelper h, int size) {
        keepLoaded(h, -4, -4, size + 4, size + 4);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, 1, z, Blocks.STONE);
                for (int y = 2; y <= 8; y++) {
                    h.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
    }

    /** Seeds a test fixture with evidence only when the real sight ray reaches the requested block. */
    static boolean observeVisible(GameTestHelper h, CompanionEntity companion, BlockPos target) {
        // Task fixtures need an owner so a sighting survives the current-tick view window.
        if (companion.ownerUuid() == null) companion.setOwner(java.util.UUID.randomUUID());
        var level = h.getLevel();
        double[] offsets = {0.2, 0.5, 0.8};
        for (double x : offsets) {
            for (double y : offsets) {
                for (double z : offsets) {
                    Vec3 endpoint = new Vec3(target.getX() + x, target.getY() + y, target.getZ() + z);
                    var sight = ResourceSight.rayTo(level, companion.getEyePosition(), endpoint, target);
                    ResourceSight.Outcome outcome;
                    do {
                        outcome = sight.step(128);
                    } while (outcome == ResourceSight.Outcome.PENDING);
                    if (outcome != ResourceSight.Outcome.VISIBLE || sight.hitBlock() == null) continue;
                    BlockPos hit = sight.hitBlock();
                    BlockPos viewpoint = companion.blockPosition();
                    String id = BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(hit).getBlock())
                            .toString();
                    GameTestSupport.serverState(level.getServer())
                            .resources()
                            .observe(
                                    companion,
                                    new ResourceObservation(
                                            id,
                                            new ResourceObservation.Location(
                                                    level.dimension()
                                                            .identifier()
                                                            .toString(),
                                                    new Pos(hit.getX(), hit.getY(), hit.getZ())),
                                            new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                            level.getServer().overworld().getGameTime()));
                    return true;
                }
            }
        }
        return false;
    }

    /** A stone floor at {@code y} with clear air above it, for tests that need a world band of their own. */
    static void platformAt(GameTestHelper h, int y, int size) {
        keepLoaded(h, -4, -4, size + 4, size + 4);
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
