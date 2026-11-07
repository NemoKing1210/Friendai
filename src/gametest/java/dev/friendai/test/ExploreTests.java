package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.exploration.minecraft.task.ExploreTask;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.block.BlockMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class ExploreTests {
    @GameTest(maxTicks = 80)
    public void ignoresStaleNearestMemoryWhenAnotherResourceIsVisible(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var level = h.getLevel();
        var resources = GameTestSupport.runtime(level.getServer()).resourceCandidates();
        h.setBlock(6, 2, 2, Blocks.ACACIA_LOG);
        BlockPos stale = h.absolutePos(new BlockPos(4, 2, 2));
        BlockPos visible = h.absolutePos(new BlockPos(6, 2, 2));
        for (BlockPos pos : java.util.List.of(stale, visible)) {
            resources.observe(
                    companion,
                    new ResourceObservation(
                            "minecraft:acacia_log",
                            new ResourceObservation.Location(
                                    level.dimension().identifier().toString(),
                                    new Pos(pos.getX(), pos.getY(), pos.getZ())),
                            new Pos(
                                    companion.blockPosition().getX(),
                                    companion.blockPosition().getY(),
                                    companion.blockPosition().getZ()),
                            level.getServer().overworld().getGameTime() - 300));
        }
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 200, () -> false);
        runner.setEventSink(result);
        runner.start(
                new ExploreTask(BlockMatcher.parse("#minecraft:logs").orElseThrow(), false, 0, 16, 128, 600),
                "explore");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "visible log should be found despite stale nearer memory");
        h.succeed();
    }

    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;
        String debug;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onDebug(String text) {
            debug = text;
        }
    }

    @GameTest(maxTicks = 80)
    public void failedUndergroundExploreLogsMiningFailureDetails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 200, () -> false);
        runner.setEventSink(result);
        runner.start(
                new ExploreTask(BlockMatcher.parse("minecraft:diamond_ore").orElseThrow(), true, -20, 8, 64, 60),
                "explore");
        for (int i = 0; i < 80 && result.status == null; i++) {
            runner.tick();
        }
        h.assertTrue(result.status == TaskStatus.FAILED, "underground search should fail: " + result.summary);
        h.assertTrue(result.debug != null && result.debug.contains("why="), "missing mining reason: " + result.debug);
        h.assertTrue(
                result.debug.contains("state=") && result.debug.contains("phase="),
                "missing mining state/phase: " + result.debug);
        h.assertTrue(result.debug.contains("broken=0"), "missing broken block count: " + result.debug);
        h.succeed();
    }

    @GameTest(maxTicks = 80)
    public void surfaceExploreRecordsARememberedLogAsARecentSighting(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var level = h.getLevel();
        h.setBlock(10, 2, 2, Blocks.ACACIA_LOG);
        BlockPos log = h.absolutePos(new BlockPos(10, 2, 2));
        var state = GameTestSupport.runtime(level.getServer());
        var config = state.config();
        int oldRadius = config.tasks.resources.observationRadius;
        config.tasks.resources.observationRadius = 1;
        state.applyConfig(config);
        state.observations().unregister(companion);
        // Remembered but outside the recent window: only ExploreTask can turn this into a recent sighting.
        state.resourceCandidates()
                .observe(
                        companion,
                        new ResourceObservation(
                                "minecraft:acacia_log",
                                new ResourceObservation.Location(
                                        level.dimension().identifier().toString(),
                                        new Pos(log.getX(), log.getY(), log.getZ())),
                                new Pos(2, 2, 2),
                                level.getServer().overworld().getGameTime() - 300));
        h.assertTrue(
                state.resourceCandidates().confirmedRecent(companion, 16).isEmpty(),
                "the fixture must be remembered, not recent");

        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 200, () -> false);
        runner.setEventSink(result);
        runner.start(
                new ExploreTask(BlockMatcher.parse("#minecraft:logs").orElseThrow(), false, 0, 16, 128, 600),
                "explore");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
        WorldFacts world = WorldScan.scan(level, companion, 16, false, false);
        h.assertValueEqual(
                world.blocksNearby().getOrDefault("minecraft:acacia_log", 0),
                1,
                "the explored log should be recorded as a recent sighting");
        config.tasks.resources.observationRadius = oldRadius;
        state.applyConfig(config);
        h.succeed();
    }
}
