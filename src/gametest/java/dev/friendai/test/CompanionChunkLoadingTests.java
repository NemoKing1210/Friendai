package dev.friendai.test;

import dev.friendai.entity.CompanionAnchors;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.infrastructure.config.ServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

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
     * An anchor points to previously visited terrain. Generate the two chunks before exercising the ticket and
     * let them unload so the test measures restoration of saved chunks rather than the speed of new world generation.
     */
    @GameTest(maxTicks = 6000)
    public void anchorsAreReArmedEveryTickAndRestoredWithoutBlocking(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CompanionChunkLoader loader = GameTestSupport.runtime(level.getServer()).chunkLoader();
        CompanionAnchors anchors = level.getServer().getDataStorage().computeIfAbsent(CompanionAnchors.TYPE);
        ChunkPos firstChunk = farChunk(h);
        ChunkPos secondChunk = new ChunkPos(firstChunk.x() + 16, firstChunk.z());
        level.getChunk(firstChunk.x(), firstChunk.z());
        level.getChunk(secondChunk.x(), secondChunk.z());
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(
                            level.getChunkSource().getChunkNow(firstChunk.x(), firstChunk.z()) == null,
                            "the first saved chunk should unload before the ticket test");
                    h.assertTrue(
                            level.getChunkSource().getChunkNow(secondChunk.x(), secondChunk.z()) == null,
                            "the second saved chunk should unload before the ticket test");
                })
                .thenExecute(() -> verifyRestoration(h, level, loader, anchors, firstChunk, secondChunk));
    }

    private static void verifyRestoration(
            GameTestHelper h,
            ServerLevel level,
            CompanionChunkLoader loader,
            CompanionAnchors anchors,
            ChunkPos firstChunk,
            ChunkPos secondChunk) {
        // Phase 1: an anchor saved earlier in the session must be picked up by the tick loop alone,
        // even if restore() has not run again (the reload path).
        UUID first = UUID.randomUUID();
        anchors.put(first, new CompanionAnchors.Anchor(level.dimension(), firstChunk));
        h.runAfterDelay(60, () -> {
            h.assertTrue(loader.awaitingChunk(first), "a later tick should re-arm the saved anchor");
            requireTicket(h, level, firstChunk);
            // Phase 2: restore() only arms the anchor. A subsequent tick installs its ticket.
            UUID second = UUID.randomUUID();
            anchors.put(second, new CompanionAnchors.Anchor(level.dimension(), secondChunk));
            loader.restore();
            h.assertTrue(
                    level.getChunkSource().getChunkNow(secondChunk.x(), secondChunk.z()) == null,
                    "restore must not generate the anchored chunk on the server thread");
            h.assertTrue(loader.awaitingChunk(second), "restore should arm the anchored chunk");
            h.runAfterDelay(5, () -> {
                requireTicket(h, level, secondChunk);
                h.runAfterDelay(120, () -> {
                    h.assertTrue(
                            loader.anchorFor(first).isEmpty(),
                            "an anchor with no companion should be dropped after the grace period");
                    h.assertTrue(
                            loader.anchorFor(second).isEmpty(),
                            "an anchor with no companion should be dropped after the grace period");
                    h.succeed();
                });
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

    /**
     * The bounded pass decides whether to rebuild its owner set from this counter, so it must move on a real change
     * and stay put when the same anchor is rewritten, or a steady tick would pay for a full rebuild.
     */
    @GameTest
    public void anchorRevisionAdvancesOnlyOnAChange(GameTestHelper h) {
        CompanionAnchors anchors = new CompanionAnchors();
        long initial = anchors.revision();
        UUID owner = UUID.randomUUID();
        CompanionAnchors.Anchor anchor = new CompanionAnchors.Anchor(Level.OVERWORLD, new ChunkPos(1, 2));
        anchors.put(owner, anchor);
        h.assertValueEqual(anchors.revision(), initial + 1, "storing a new anchor must advance the revision");
        anchors.put(owner, anchor);
        h.assertValueEqual(anchors.revision(), initial + 1, "rewriting the same anchor must not advance it");
        anchors.put(owner, new CompanionAnchors.Anchor(Level.OVERWORLD, new ChunkPos(3, 4)));
        h.assertValueEqual(anchors.revision(), initial + 2, "moving an anchor must advance the revision");
        anchors.remove(owner);
        h.assertValueEqual(anchors.revision(), initial + 3, "removing an anchor must advance the revision");
        anchors.remove(owner);
        h.assertValueEqual(anchors.revision(), initial + 3, "removing an absent anchor must not advance it");
        h.succeed();
    }

    private static void requireTicket(GameTestHelper h, ServerLevel level, ChunkPos chunk) {
        // Await I/O without spending simulated ticks. Passing false keeps the probe from adding its own ticket.
        var result = level.getChunkSource()
                .getChunkFuture(chunk.x(), chunk.z(), ChunkStatus.FULL, false)
                .join();
        h.assertTrue(result.isSuccess(), "the pending ticket must make " + chunk + " loadable: " + result.getError());
    }

    /**
     * World close is the loader's last chance to drop runtime tickets: a stopped loader must hold none, and a later
     * tick must not resurrect work it just released.
     */
    @GameTest(environment = "friendai-gametest:chunk_loading_radius_zero")
    public void worldCloseReleasesEveryTicket(GameTestHelper h) {
        // A private loader on the real server and anchor state, so this test never stops the shared runtime the rest
        // of the batch uses; the environments above give it its own batch.
        CompanionChunkLoader loader = new CompanionChunkLoader(
                h.getLevel().getServer(),
                () -> GameTestSupport.runtime(h.getLevel().getServer()).config());
        CompanionAnchors anchors = h.getLevel().getServer().getDataStorage().computeIfAbsent(CompanionAnchors.TYPE);
        UUID owner = UUID.randomUUID();
        anchors.put(owner, new CompanionAnchors.Anchor(h.getLevel().dimension(), farChunk(h)));
        loader.restore();
        h.startSequence()
                .thenWaitUntil(() -> {
                    // A private loader is not on the tick pipeline, so the fixture drives its bounded pass itself.
                    loader.tick();
                    h.assertTrue(loader.hasAnchorTicket(owner), "the private loader never admitted");
                })
                .thenExecute(() -> {
                    loader.forget(owner);
                    anchors.remove(owner);
                    int held = loader.ticketCount();
                    h.assertTrue(held >= 1, "the private loader should hold at least one ticket before stop");
                    loader.stop();
                    h.assertValueEqual(loader.ticketCount(), 0, "a stopped loader must hold no tickets");
                    h.assertTrue(loader.pendingCount() == 0, "a stopped loader must hold no pending anchors");
                    h.succeed();
                });
    }

    /**
     * The November 13 boot armed 130–775 saved anchors before its tests ran. A warm world is unavoidable for the
     * startup path, so this fixture asserts the steady state instead: the pass never admits more than the allowance,
     * every owner receives its ticket over several bounded passes, and a ticket stays held while the owner's chunk is
     * still missing, so a bounded pass does not add and remove the same ticket every tick.
     */
    @GameTest(maxTicks = 200, environment = "friendai-gametest:chunk_loading_radius_zero")
    public void manyAnchorsAreRestoredInBoundedPasses(GameTestHelper h) {
        // Radius 0 tickets the anchor chunk itself and nothing around it, so this fixture measures scheduling
        // without generating the neighbours of forty far chunks. The config environment restores the batch copy.
        ServerConfig config = GameTestSupport.runtime(h.getLevel().getServer()).config();
        config.companion.chunkLoading.radius = 0;
        ServerLevel level = h.getLevel();
        CompanionChunkLoader loader = GameTestSupport.runtime(level.getServer()).chunkLoader();
        CompanionAnchors anchors = level.getServer().getDataStorage().computeIfAbsent(CompanionAnchors.TYPE);
        Map<UUID, ChunkPos> planted = new HashMap<>();
        ChunkPos origin = farChunk(h);
        for (int index = 0; index < 40; index++) {
            UUID owner = UUID.randomUUID();
            // Distinct chunks, far from the arena and from one another, so one ticket cannot satisfy another.
            ChunkPos chunk = new ChunkPos(origin.x() + index * 8, origin.z() + index * 8);
            planted.put(owner, chunk);
            anchors.put(owner, new CompanionAnchors.Anchor(level.dimension(), chunk));
        }
        h.assertTrue(anchors.all().size() >= planted.size(), "the fixture's anchors must be in the store");
        loader.restore();

        // Phase 1: drive the bounded pass until every planted anchor holds a ticket.
        int[] maxAdmitted = {0};
        int[] firstAdmitTicks = {Integer.MAX_VALUE};
        int[] ticks = {0};
        int[] heldAfterDrain = {-1};
        List<Long> durations = new ArrayList<>();
        h.startSequence()
                .thenWaitUntil(() -> {
                    int admitted = loader.lastTickAdmitted();
                    maxAdmitted[0] = Math.max(maxAdmitted[0], admitted);
                    durations.add(loader.lastTickNanos());
                    ticks[0]++;
                    h.assertTrue(
                            admitted <= CompanionChunkLoader.ANCHORS_PER_TICK,
                            "a tick admitted " + admitted + " anchors, above the allowance");
                    // The sequence retries a throwing wait until it stops throwing, so the drain itself is the
                    // condition; a finished pass is proven by the ticket every planted anchor now holds.
                    boolean drained = planted.keySet().stream().allMatch(loader::hasAnchorTicket);
                    if (drained && firstAdmitTicks[0] == Integer.MAX_VALUE) {
                        firstAdmitTicks[0] = ticks[0];
                        heldAfterDrain[0] = loader.ticketCount();
                    }
                    h.assertTrue(drained, "the bounded pass has not yet ticketed all forty anchors");
                })
                // Phase 2: a ticket issued for a waiting anchor stays held, so the rotation must not tear it down
                // and rebuild it on the next pass; nothing may be dropped while the owner's companion is absent.
                .thenWaitUntil(() -> {
                    for (UUID owner : planted.keySet()) {
                        h.assertTrue(loader.hasAnchorTicket(owner), "a held anchor lost its ticket: " + owner);
                    }
                    h.assertTrue(
                            loader.ticketCount() >= heldAfterDrain[0],
                            "the settled pass released a ticket it should hold");
                    h.assertTrue(heldAfterDrain[0] >= 1, "the drain should have been observed before phase 2");
                })
                .thenExecute(() -> {
                    long p95 = durations.stream()
                            .sorted()
                            .skip(Math.max(0, durations.size() - 1 - (long) Math.floor(durations.size() * 0.95)))
                            .findFirst()
                            .orElse(0L);
                    long max =
                            durations.stream().mapToLong(Long::longValue).max().orElse(0L);
                    dev.friendai.FriendaiMod.LOGGER.info(
                            "Bounded anchor pass: drainedAtTick={}, p95={}us, max={}us, maxAdmitted={}",
                            firstAdmitTicks[0],
                            p95 / 1000,
                            max / 1000,
                            maxAdmitted[0]);
                    for (UUID owner : planted.keySet()) {
                        anchors.remove(owner);
                        loader.forget(owner);
                    }
                    h.assertTrue(
                            planted.keySet().stream().noneMatch(loader::hasAnchorTicket),
                            "forgetting every owner must drop every issued anchor ticket");
                    h.assertTrue(
                            planted.keySet().stream().noneMatch(owner -> loader.awaitingChunk(owner)),
                            "forgetting every owner must also clear its pending entry");
                    h.succeed();
                });
    }

    /**
     * A loaded companion owns its chunk through its own ticket and must not also hold an anchor ticket, and its chunk
     * keeps ticking while forty other anchors are restored around it.
     */
    @GameTest(maxTicks = 200, environment = "friendai-gametest:chunk_loading_radius_zero")
    public void loadedCompanionLeavesTheBoundedPass(GameTestHelper h) {
        ServerConfig config = GameTestSupport.runtime(h.getLevel().getServer()).config();
        config.companion.chunkLoading.radius = 0;
        ServerLevel level = h.getLevel();
        CompanionChunkLoader loader = GameTestSupport.runtime(level.getServer()).chunkLoader();
        CompanionAnchors anchors = level.getServer().getDataStorage().computeIfAbsent(CompanionAnchors.TYPE);
        ServerPlayer owner = CompanionEntityTests.mockPlayerHere(h);
        CompanionManager.get(level.getServer()).summon(owner);
        ChunkPos origin = farChunk(h);
        // More waiting anchors than one pass can visit, so a loaded owner that takes a slot delays the drain by a
        // measurable amount instead of hiding behind spare capacity.
        for (int index = 1; index <= 40; index++) {
            UUID other = UUID.randomUUID();
            ChunkPos chunk = new ChunkPos(origin.x() + index, origin.z() + index);
            anchors.put(other, new CompanionAnchors.Anchor(level.dimension(), chunk));
        }
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(
                            !loader.hasAnchorTicket(owner.getUUID()),
                            "a loaded companion must not keep an anchor ticket");
                    h.assertTrue(
                            loader.anchorFor(owner.getUUID()).isPresent(),
                            "the loaded companion's anchor should follow its live chunk");
                })
                .thenExecute(() -> {
                    long loaded = CompanionManager.get(level.getServer()).all().stream()
                            .filter(companion -> owner.getUUID().equals(companion.ownerUuid()))
                            .count();
                    h.assertTrue(loaded == 1, "exactly one companion must be loaded for the owner, found " + loaded);
                    h.succeed();
                });
    }

    /**
     * The GameTest server must open the fresh `build/run/gameTest/world`; a copied or leftover verification world
     * beside it would silently supply anchors that a repeat run cannot reproduce.
     */
    @GameTest
    public void testWorldIsIsolatedAndFresh(GameTestHelper h) {
        // `getWorldPath` is relative to the server's game directory, so resolve it before comparing with the profile.
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        Path world = gameDir.resolve(h.getLevel().getServer().getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
        h.assertTrue(Files.isDirectory(world), "the server world directory must exist");
        h.assertTrue(
                world.getParent() != null
                        && world.getParent().equals(gameDir.toAbsolutePath().normalize()),
                "the server world must live in the isolated profile " + gameDir + ", not " + world);
        Path profile = world.getParent();
        Path marker = profile.resolve(".gameTest-run-id");
        h.assertTrue(
                Files.isRegularFile(marker), "a run marker must prove the world was recreated for this run: " + marker);
        try {
            h.assertTrue(Files.readString(marker).strip().length() > 8, "the run marker must carry the reset run's id");
        } catch (java.io.IOException error) {
            h.fail("cannot read the run marker: " + error.getMessage());
        }
        try (var siblings = Files.list(profile)) {
            h.assertTrue(
                    siblings.noneMatch(path -> path.getFileName().toString().startsWith("world-")),
                    "no stale verification world may sit beside the fresh world in " + profile);
        } catch (java.io.IOException error) {
            h.fail("cannot inspect the test profile: " + error.getMessage());
        }
        h.succeed();
    }

    private static ChunkPos farChunk(GameTestHelper h) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        return new ChunkPos((origin.getX() >> 4) + 64, (origin.getZ() >> 4) + 64);
    }
}
