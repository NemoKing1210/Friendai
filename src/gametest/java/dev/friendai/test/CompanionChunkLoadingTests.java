package dev.friendai.test;

import dev.friendai.entity.CompanionAnchors;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** The persisted anchor that lets a companion keep (and restore) its own loaded chunk. */
public class CompanionChunkLoadingTests {
    @GameTest
    public void summonRecordsAnAnchor(GameTestHelper h) {
        ServerPlayer player = CompanionEntityTests.mockPlayerHere(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        h.runAfterDelay(5, () -> {
            var anchor = GameTestSupport.runtime(h.getLevel().getServer())
                    .chunkLoader()
                    .anchorFor(player.getUUID());
            h.assertTrue(anchor.isPresent(), "an anchor should be recorded for the owner");
            h.assertTrue(
                    anchor.get().chunk().equals(companion.chunkPosition()),
                    "the anchor should name the companion's chunk");
            h.succeed();
        });
    }

    @GameTest
    public void dismissDropsTheAnchor(GameTestHelper h) {
        ServerPlayer player = CompanionEntityTests.mockPlayerHere(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(player);
        h.runAfterDelay(5, () -> {
            manager.dismiss(player);
            h.runAfterDelay(5, () -> {
                var loader = GameTestSupport.runtime(h.getLevel().getServer()).chunkLoader();
                h.assertTrue(
                        loader.anchorFor(player.getUUID()).isEmpty(),
                        "dismissing the companion should drop its anchor");
                h.succeed();
            });
        });
    }

    /**
     * The ticket is armed by the tick loop synchronously; the chunk it names is generated asynchronously, on a worker
     * starved by the rest of the shard, so the load is awaited inside the test's own budget instead of being asserted
     * at a fixed tick. The budget covers a fresh pair of chunk neighbourhoods; a failing run still ends, just later.
     */
    @GameTest(maxTicks = 6000)
    public void anchorsAreReArmedEveryTickAndRestoredWithoutBlocking(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CompanionChunkLoader loader = GameTestSupport.runtime(level.getServer()).chunkLoader();
        CompanionAnchors anchors = level.getServer().getDataStorage().computeIfAbsent(CompanionAnchors.TYPE);

        // Phase 1: an anchor saved earlier in the session must be picked up by the tick loop alone,
        // even if restore() has not run again (the reload path).
        UUID first = UUID.randomUUID();
        ChunkPos firstChunk = farChunk(h);
        anchors.put(first, new CompanionAnchors.Anchor(level.dimension(), firstChunk));
        h.runAfterDelay(60, () -> {
            h.assertTrue(loader.awaitingChunk(first), "a later tick should re-arm the saved anchor");
            awaitChunk(h, level, firstChunk, 2400, () -> {
                // Phase 2: restore() over an anchor whose chunk is not in memory must not generate it
                // synchronously; the pending ticket loads it a few ticks later.
                UUID second = UUID.randomUUID();
                ChunkPos secondChunk = new ChunkPos(firstChunk.x() + 16, firstChunk.z());
                anchors.put(second, new CompanionAnchors.Anchor(level.dimension(), secondChunk));
                loader.restore();
                h.assertTrue(
                        level.getChunkSource().getChunkNow(secondChunk.x(), secondChunk.z()) == null,
                        "restore must not generate the anchored chunk on the server thread");
                h.assertTrue(loader.awaitingChunk(second), "restore should arm the anchored chunk");

                awaitChunk(
                        h,
                        level,
                        secondChunk,
                        2400,
                        () -> h.runAfterDelay(120, () -> {
                            h.assertTrue(
                                    loader.anchorFor(first).isEmpty(),
                                    "an anchor with no companion should be dropped after the grace period");
                            h.assertTrue(
                                    loader.anchorFor(second).isEmpty(),
                                    "an anchor with no companion should be dropped after the grace period");
                            h.succeed();
                        }));
            });
        });
    }

    @GameTest
    public void anchorCodecRoundTrips(GameTestHelper h) {
        CompanionAnchors anchors = new CompanionAnchors();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        anchors.put(first, new CompanionAnchors.Anchor(Level.OVERWORLD, new ChunkPos(1, 2)));
        anchors.put(second, new CompanionAnchors.Anchor(Level.NETHER, new ChunkPos(-3, 4)));
        CompoundTag encoded = (CompoundTag) CompanionAnchors.TYPE
                .codec()
                .encodeStart(NbtOps.INSTANCE, anchors)
                .getOrThrow();
        CompanionAnchors decoded =
                CompanionAnchors.TYPE.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
        h.assertTrue(decoded.all().equals(anchors.all()), "anchors should survive a save/load round trip");
        h.succeed();
    }

    private static void awaitChunk(GameTestHelper h, ServerLevel level, ChunkPos chunk, int remaining, Runnable then) {
        if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
            then.run();
        } else if (remaining <= 0) {
            h.assertTrue(false, "the pending ticket should load the anchored chunk");
        } else {
            h.runAfterDelay(5, () -> awaitChunk(h, level, chunk, remaining - 5, then));
        }
    }

    private static ChunkPos farChunk(GameTestHelper h) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        return new ChunkPos((origin.getX() >> 4) + 64, (origin.getZ() >> 4) + 64);
    }
}
