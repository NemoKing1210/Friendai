package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.ExpeditionChunkHorizon;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.ObtainTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

public final class ResourceJourneyTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String tool, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String tool, String reason) {}
    }

    @GameTest(maxTicks = 40)
    public void disabledExpeditionsDoNotRequestChunkTickets(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var config = state.config();
        boolean oldExpeditions = config.tasks.resources.allowExpeditions;
        config.tasks.resources.allowExpeditions = false;
        state.applyConfig(config);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        ExpeditionChunkHorizon horizon = state.chunkLoader().expeditionHorizon();
        var lease = horizon.request(owner.getUUID(), h.getLevel(), new ChunkPos(128, 128));
        h.assertTrue(lease.expired() && !lease.ready(), "disabled expedition should stop at the local frontier");
        h.assertTrue(
                horizon.activeLeases() == 0 && horizon.ticketKeys() == 0, "disabled request must not hold tickets");
        lease.close();
        config.tasks.resources.allowExpeditions = oldExpeditions;
        state.applyConfig(config);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void cancelledJourneyReleasesHorizonIdempotently(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var config = state.config();
        boolean oldExpeditions = config.tasks.resources.allowExpeditions;
        boolean oldChunkLoading = config.companion.chunkLoading.enabled;
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        ExpeditionChunkHorizon horizon = state.chunkLoader().expeditionHorizon();
        var lease = horizon.request(owner.getUUID(), h.getLevel(), new ChunkPos(128, 128));
        h.assertTrue(horizon.activeLeases() == 1 && horizon.ticketKeys() == 1, "next leg should have one ticket");
        lease.close();
        lease.close();
        h.assertTrue(
                horizon.activeLeases() == 0 && horizon.ticketKeys() == 0,
                "repeated cancel should release exactly once");
        config.tasks.resources.allowExpeditions = oldExpeditions;
        config.companion.chunkLoading.enabled = oldChunkLoading;
        state.applyConfig(config);
        h.succeed();
    }

    @GameTest(maxTicks = 2400)
    public void passCoalRestartAskThenReturnAndCollect(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 64);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 301, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 301, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        h.setBlock(8, 302, 2, Blocks.COAL_ORE);
        BlockPos ore = h.absolutePos(new net.minecraft.core.BlockPos(8, 302, 2));
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, ore),
                "coal ore should be directly visible before restart");

        var server = h.getLevel().getServer();
        var beforeRestart = FriendaiServerState.get(server);
        beforeRestart.knowledge().flushAll();
        FriendaiServerState.close(server);
        var afterRestart = FriendaiServerState.get(server);
        dev.friendai.entity.CompanionManager.get(server).register(companion);
        afterRestart.observations().register(companion);
        String dimension = h.getLevel().dimension().identifier().toString();
        var origin = companion.blockPosition();
        var request = new dev.friendai.features.knowledge.ResourceQuery.Request(
                java.util.Set.of("minecraft:coal_ore"),
                dimension,
                new Pos(origin.getX(), origin.getY(), origin.getZ()),
                32,
                5,
                true);
        var remembered = dev.friendai.features.knowledge.ResourceQuery.find(
                afterRestart.knowledge().book(owner.getUUID()), request);
        h.assertTrue(!remembered.isEmpty(), "restart should restore the previously observed coal site");
        BlockPos rememberedOre = new BlockPos(
                remembered.getFirst().site().members().iterator().next().x(),
                remembered.getFirst().site().members().iterator().next().y(),
                remembered.getFirst().site().members().iterator().next().z());
        var sight = dev.friendai.perception.resource.ResourceSight.rayTo(
                h.getLevel(),
                companion.getEyePosition(),
                net.minecraft.world.phys.Vec3.atCenterOf(rememberedOre),
                rememberedOre);
        dev.friendai.perception.resource.ResourceSight.Outcome sightOutcome;
        do {
            sightOutcome = sight.step(128);
        } while (sightOutcome == dev.friendai.perception.resource.ResourceSight.Outcome.PENDING);
        h.assertTrue(
                sightOutcome == dev.friendai.perception.resource.ResourceSight.Outcome.VISIBLE,
                "restored member should still be directly visible from the remembered approach: " + rememberedOre);

        Result result = new Result();
        dev.friendai.entity.CompanionManager.get(server).register(companion);
        ExecutionCoordinator runner = companion.executionCoordinator();
        runner.setListener(result);
        runner.start(
                new dev.friendai.task.builtin.VisitResourceTask(
                        remembered.getFirst().site().id(), dimension, 400),
                "visit_resource");
        int[] phase = {0};
        h.succeedWhen(() -> {
            afterRestart.observations().tick(server, server.overworld().getGameTime());
            if (phase[0] == 0) {
                GameTestSupport.observeVisible(h, companion, rememberedOre);
                // GameTest callbacks run after entity ticks; let the visit consume this tick's
                // valid direct evidence instead of waiting for the following server tick.
                runner.tick();
            }
            if (phase[0] == 0) {
                h.assertTrue(
                        result.status != null && result.status != TaskStatus.RUNNING,
                        "waiting for remembered visit to finish");
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "remembered visit failed: " + result.status + " " + result.summary);
                result.status = null;
                phase[0] = 1;
                runner.start(
                        new dev.friendai.features.mining.minecraft.task.MineBlocksTask(
                                dev.friendai.features.mining.minecraft.mining.BlockMatcher.parse("minecraft:coal_ore")
                                        .orElseThrow(),
                                1,
                                64),
                        "mine_blocks");
                return;
            }
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "waiting for mined coal to be collected");
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS,
                    "coal collection failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    companion.inventory().countItem(Items.COAL) >= 1, "the visited coal should be mined and collected");
        });
    }

    @GameTest(maxTicks = 2400)
    public void unknownIronCrossesChunksWithoutXray(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 64);
        int startX = -1;
        for (int candidate = 0; candidate <= 40; candidate++) {
            int startChunk = h.absolutePos(new BlockPos(candidate, 201, 2)).getX() >> 4;
            int viewChunk = h.absolutePos(new BlockPos(candidate + 4, 201, 2)).getX() >> 4;
            int oreChunk = h.absolutePos(new BlockPos(candidate + 18, 201, 2)).getX() >> 4;
            if (startChunk != viewChunk && viewChunk != oreChunk && startChunk != oreChunk) {
                startX = candidate;
                break;
            }
        }
        h.assertTrue(startX >= 0, "fixture needs three nearby chunks with a navigable approach");
        var owner = GameTestSupport.mockPlayerAt(h, startX, 201, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, startX, 201, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        var server = h.getLevel().getServer();
        var state = FriendaiServerState.get(server);
        state.observations().register(companion);
        String dimension = h.getLevel().dimension().identifier().toString();

        BlockPos start = h.absolutePos(new BlockPos(startX, 201, 2));
        int viewX = startX + 4;
        int oreX = startX + 18;
        var unknownRequest = new dev.friendai.features.knowledge.ResourceQuery.Request(
                java.util.Set.of("minecraft:iron_ore"),
                dimension,
                new Pos(start.getX(), start.getY(), start.getZ()),
                64,
                5,
                true);
        h.assertTrue(
                dev.friendai.features.knowledge.ResourceQuery.find(
                                state.knowledge().book(owner.getUUID()), unknownRequest)
                        .isEmpty(),
                "iron must remain unknown before a direct sight ray");

        h.setBlock(oreX, 202, 2, Blocks.IRON_ORE);
        BlockPos ore = h.absolutePos(new BlockPos(oreX, 202, 2));
        BlockPos viewpoint = h.absolutePos(new BlockPos(viewX, 201, 2));
        h.assertTrue(
                (start.getX() >> 4) != (viewpoint.getX() >> 4)
                        && (viewpoint.getX() >> 4) != (ore.getX() >> 4)
                        && (start.getX() >> 4) != (ore.getX() >> 4),
                "start, sighting and ore must occupy three distinct chunks");
        companion.teleportTo(viewpoint.getX() + 0.5, viewpoint.getY(), viewpoint.getZ() + 0.5);
        companion.executionCoordinator().start(new dev.friendai.features.navigation.minecraft.task.StayTask(), "stay");
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, ore), "iron ore should be learned only from direct sight");
        companion.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);

        var remembered = dev.friendai.features.knowledge.ResourceQuery.find(
                state.knowledge().book(owner.getUUID()), unknownRequest);
        h.assertTrue(!remembered.isEmpty(), "direct sight should create a remembered cross-chunk iron site");
        Pos recordedApproach = remembered.getFirst().site().approach();
        h.assertTrue(
                (recordedApproach.x() >> 4) != (ore.getX() >> 4)
                        && (recordedApproach.x() >> 4) != (start.getX() >> 4)
                        && Math.hypot(recordedApproach.x() - ore.getX(), recordedApproach.z() - ore.getZ()) <= 16,
                "the recorded sighting must stay in its own chunk and within direct observation range");
        Result result = new Result();
        dev.friendai.entity.CompanionManager.get(server).register(companion);
        ExecutionCoordinator runner = companion.executionCoordinator();
        runner.setListener(result);
        runner.start(
                new dev.friendai.task.builtin.VisitResourceTask(
                        remembered.getFirst().site().id(), dimension, 1000),
                "visit_resource");
        int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0) GameTestSupport.observeVisible(h, companion, ore);
            state.observations().tick(server, server.overworld().getGameTime());
            // GameTestHelper callbacks run after entity ticks; apply the task once more so the
            // direct sight evidence produced above is consumed in the same logical game tick.
            runner.tick();
            if (phase[0] == 0) {
                h.assertTrue(
                        result.status != null && result.status != TaskStatus.RUNNING,
                        "waiting for cross-chunk visit to finish");
                var diagnostic = dev.friendai.perception.resource.ResourceSight.rayTo(
                        h.getLevel(), companion.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(ore), ore);
                dev.friendai.perception.resource.ResourceSight.Outcome directOutcome;
                do {
                    directOutcome = diagnostic.step(128);
                } while (directOutcome == dev.friendai.perception.resource.ResourceSight.Outcome.PENDING);
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "cross-chunk visit failed: " + result.status + " " + result.summary
                                + "; companion=" + companion.blockPosition()
                                + "; approach=" + remembered.getFirst().site().approach()
                                + "; member=" + ore + "; direct_ray=" + directOutcome);
                result.status = null;
                phase[0] = 1;
                runner.start(
                        new dev.friendai.features.mining.minecraft.task.MineBlocksTask(
                                dev.friendai.features.mining.minecraft.mining.BlockMatcher.parse("minecraft:iron_ore")
                                        .orElseThrow(),
                                1,
                                16),
                        "mine_blocks");
                return;
            }
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "waiting for remembered iron to be collected");
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS,
                    "iron collection failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    companion.inventory().countItem(Items.RAW_IRON) >= 1,
                    "the remembered iron must be mined and collected without a remote scan");
        });
    }

    @GameTest(maxTicks = 2400)
    public void visitThenMineReplansFromObservedInventory(GameTestHelper h) {
        GameTestSupport.platformAt(h, 100, 64);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 101, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 101, 2);
        companion.setOwner(owner.getUUID());
        h.setBlock(8, 101, 2, Blocks.OAK_LOG);
        h.setBlock(9, 101, 2, Blocks.OAK_LOG);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        dev.friendai.entity.CompanionManager.get(h.getLevel().getServer()).register(companion);
        state.observations().register(companion);
        String dimension = h.getLevel().dimension().identifier().toString();
        var logA = h.absolutePos(new net.minecraft.core.BlockPos(8, 101, 2));
        var logB = h.absolutePos(new net.minecraft.core.BlockPos(9, 101, 2));
        var viewpoint = h.absolutePos(new net.minecraft.core.BlockPos(4, 101, 2));
        long observed = Math.max(0, h.getLevel().getGameTime() - 1);
        state.knowledge()
                .book(owner.getUUID())
                .observe(
                        new ResourceObservation(
                                "minecraft:oak_log",
                                new ResourceObservation.Location(
                                        dimension, new Pos(logA.getX(), logA.getY(), logA.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                observed),
                        "minecraft:oak_log");
        state.knowledge()
                .book(owner.getUUID())
                .observe(
                        new ResourceObservation(
                                "minecraft:oak_log",
                                new ResourceObservation.Location(
                                        dimension, new Pos(logB.getX(), logB.getY(), logB.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                observed),
                        "minecraft:oak_log");
        h.assertTrue(
                !state.resources().plannerCandidates(companion, 20).isEmpty(),
                "historical visible logs should become a resource candidate");
        var facts = dev.friendai.task.obtain.WorldScan.scan(h.getLevel(), companion, 32, false, true);
        var plan = dev.friendai.features.acquisition.ObtainPlanner.plan(
                "minecraft:oak_log",
                2,
                java.util.Map.of(),
                dev.friendai.task.crafting.RecipeIndex.of(h.getLevel()),
                dev.friendai.task.obtain.DropIndex.of(h.getLevel()),
                facts);
        h.assertTrue(
                !plan.steps().isEmpty()
                        && plan.steps().get(0) instanceof dev.friendai.features.acquisition.ObtainStep.VisitResource,
                "expected remembered visit plan, got "
                        + dev.friendai.features.acquisition.ObtainText.planText(plan, "minecraft:oak_log", 2));

        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 2400, () -> false);
        runner.setListener(result);
        var candidate = state.resources().plannerCandidates(companion, 20).get(0);
        runner.start(
                new dev.friendai.task.builtin.VisitResourceTask(candidate.siteId(), dimension, 400), "visit_resource");
        boolean[] visitComplete = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!visitComplete[0]) {
                if (result.status == null || result.status == TaskStatus.RUNNING) return;
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "visit did not finish: " + result.status + " " + result.summary);
                visitComplete[0] = true;
                result.status = null;
                runner.start(
                        new ObtainTask("minecraft:oak_log", 2, false, true, 12, 2_000, 64, 64, 1_200), "obtain_item");
                return;
            }
            if (result.status == null || result.status == TaskStatus.RUNNING) return;
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS,
                    "obtain did not finish: " + result.status + " " + result.summary);
            int logs = 0;
            for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
                if (companion.inventory().getItem(slot).is(Items.OAK_LOG))
                    logs += companion.inventory().getItem(slot).getCount();
            }
            h.assertTrue(logs >= 2, "visit must be followed by real mining; inventory held " + logs);
        });
    }
}
