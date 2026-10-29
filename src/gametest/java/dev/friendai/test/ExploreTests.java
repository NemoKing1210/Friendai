package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.exploration.minecraft.task.ExploreTask;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class ExploreTests {
    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}
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
