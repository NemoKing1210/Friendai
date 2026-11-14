package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.ExpeditionChunkHorizon;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.acquisition.minecraft.search.DropIndex;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.acquisition.minecraft.task.ResourceSearchDriver;
import dev.friendai.features.acquisition.minecraft.task.VisitResourceTask;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.SearchSession;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.features.knowledge.travel.TrailGraph;
import dev.friendai.features.navigation.minecraft.nav.ResourceJourney;
import dev.friendai.foundation.spatial.MaterialReserve;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

public final class ResourceJourneyTests {
    private record ReturnFixture(
            CompanionEntity companion, ResourceJourney journey, Pos anchor, BlockPos obstruction) {}

    private static ReturnFixture returnFixture(GameTestHelper h, boolean obstructed) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        h.setBlock(new BlockPos(5, 1, 4), Blocks.STONE);
        if (obstructed) h.setBlock(new BlockPos(5, 3, 4), Blocks.BEDROCK);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 4, 1, 4);
        BlockPos absoluteAnchor = h.absolutePos(new BlockPos(5, 2, 4));
        BlockPos absoluteLow = h.absolutePos(new BlockPos(4, 1, 4));
        Pos anchor = new Pos(absoluteAnchor.getX(), absoluteAnchor.getY(), absoluteAnchor.getZ());
        Pos low = new Pos(absoluteLow.getX(), absoluteLow.getY(), absoluteLow.getZ());
        ResourceJourney journey = new ResourceJourney(
                new TaskContext(companion, h.getLevel()),
                h.getLevel().dimension().identifier().toString(),
                anchor,
                null,
                new TrailGraph(),
                false,
                java.util.List.of(anchor, low),
                0);
        return new ReturnFixture(companion, journey, anchor, h.absolutePos(new BlockPos(5, 3, 4)));
    }

    @GameTest(maxTicks = 400)
    public void followsTheVerifiedRouteBackFromTheLowerStance(GameTestHelper h) {
        ReturnFixture fixture = returnFixture(h, false);
        TaskContext context = new TaskContext(fixture.companion(), h.getLevel());

        h.succeedWhen(() -> {
            ResourceJourney.State state = fixture.journey().tick(context);
            h.assertTrue(state == ResourceJourney.State.ARRIVED, "the verified return route did not reach its anchor");
            h.assertTrue(
                    Math.abs(fixture.companion().getY() - fixture.anchor().y()) <= 0.75,
                    "the return reported arrival from the lower stance");
            fixture.journey().stop(context);
        });
    }

    @GameTest(maxTicks = 600)
    public void obstructedVerifiedExitTriesOneAlternateWithoutBreakingBlocks(GameTestHelper h) {
        ReturnFixture fixture = returnFixture(h, true);
        fixture.companion().inventory().addItem(new net.minecraft.world.item.ItemStack(Items.IRON_INGOT, 3));
        TaskContext context = new TaskContext(fixture.companion(), h.getLevel());

        h.succeedWhen(() -> {
            ResourceJourney.State state = fixture.journey().tick(context);
            h.assertTrue(state == ResourceJourney.State.BLOCKED, "waiting for the blocked exit result");
            h.assertTrue(
                    h.getLevel().getBlockState(fixture.obstruction()).is(Blocks.BEDROCK),
                    "return recovery edited the obstruction");
            h.assertValueEqual(
                    fixture.companion().inventory().countItem(Items.IRON_INGOT),
                    3,
                    "a route refusal retains the acquired cargo");
            fixture.journey().stop(context);
        });
    }

    @GameTest(maxTicks = 900)
    public void blockedAcquisitionReturnReportsFailureAndKeepsCargo(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.setOwner(owner.getUUID());
        companion.inventory().addItem(new net.minecraft.world.item.ItemStack(Items.IRON_INGOT, 3));
        String dimension = h.getLevel().dimension().identifier().toString();
        BlockPos originBlock = h.absolutePos(new BlockPos(3, 2, 3));
        Pos origin = new Pos(originBlock.getX(), originBlock.getY(), originBlock.getZ());
        long now = h.getLevel().getServer().overworld().getGameTime();
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                dimension,
                "biome:minecraft:the_void",
                origin,
                new SearchSession.Limits(32, 128, 2_000, 0),
                now);
        TargetKind target = new TargetKind.Biome("minecraft:the_void", 32);
        ResourceSearchProfile profile = new ResourceSearchProfile(
                "biome:minecraft:the_void",
                Set.of(dimension),
                OptionalInt.empty(),
                Set.of(ResourceSearchProfile.Strategy.SURFACE));
        ResourceSearchDriver driver = new ResourceSearchDriver(
                session, target, profile, 32, false, ResourceSearchProfile.Strategy.SURFACE, null, null, null, true);
        TaskContext context = new TaskContext(companion, h.getLevel());
        driver.start(context);

        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                for (int y = 2; y <= 3; y++) {
                    if (x > 5 && x < 9 && z > 5 && z < 9) continue;
                    h.setBlock(x, y, z, Blocks.BEDROCK);
                }
                h.setBlock(x, 4, z, Blocks.BEDROCK);
            }
        }
        var blockedPosition = h.absoluteVec(new net.minecraft.world.phys.Vec3(7.5, 2, 7.5));
        companion.teleportTo(blockedPosition.x, blockedPosition.y, blockedPosition.z);
        String expected = "return route to " + origin.x() + " " + origin.y() + " " + origin.z()
                + " is blocked; cargo is kept and the chunk lease was released";

        h.succeedWhen(() -> {
            TaskStatus status = driver.tick(context);
            if (status == TaskStatus.RUNNING) return;
            h.assertTrue(
                    status == TaskStatus.FAILED, "blocked return should fail acquisition: " + driver.resultSummary());
            h.assertTrue(
                    driver.resultSummary().contains(expected),
                    "acquisition result should report the exact route failure: " + driver.resultSummary());
            h.assertValueEqual(
                    companion.inventory().countItem(Items.IRON_INGOT),
                    3,
                    "failed acquisition return must preserve the cargo");
        });
    }

    @GameTest(maxTicks = 1200)
    public void returnJourneyCannotSpendTheKitBlockReservedForWork(GameTestHelper h) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 4, 1, 4);
        companion.inventory().addItem(new net.minecraft.world.item.ItemStack(Items.DIRT));
        companion.setMaterialReserve(MaterialReserve.compose(
                java.util.Map.of("minecraft:dirt", 1), java.util.Map.of(), java.util.Map.of("minecraft:dirt", 1)));
        String dimension = h.getLevel().dimension().identifier().toString();
        BlockPos absoluteAnchor = h.absolutePos(new BlockPos(4, 5, 4));
        BlockPos absoluteLow = h.absolutePos(new BlockPos(4, 1, 4));
        Pos anchor = new Pos(absoluteAnchor.getX(), absoluteAnchor.getY(), absoluteAnchor.getZ());
        Pos low = new Pos(absoluteLow.getX(), absoluteLow.getY(), absoluteLow.getZ());
        ResourceJourney journey = new ResourceJourney(
                new TaskContext(companion, h.getLevel()),
                dimension,
                anchor,
                null,
                new TrailGraph(),
                false,
                java.util.List.of(anchor, low),
                1);
        h.succeedWhen(() -> {
            ResourceJourney.State state = journey.tick(new TaskContext(companion, h.getLevel()));
            h.assertTrue(state == ResourceJourney.State.BLOCKED, "waiting for the return route to report blocked");
            h.assertTrue(
                    companion.inventory().countItem(Items.DIRT) == 1,
                    "the return route spent the dirt reserved for the kit");
            journey.stop(new TaskContext(companion, h.getLevel()));
        });
    }

    @GameTest(maxTicks = 40)
    public void executionReserveKeepsWorkAndReturnSharesSeparate(GameTestHelper h) {
        GameTestSupport.platform(h, 24);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Task task = new Task() {
            @Override
            public String type() {
                return "return_reserve_test";
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                return TaskStatus.RUNNING;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public java.util.Map<String, Integer> materialNeeds(TaskContext context) {
                return java.util.Map.of("minecraft:dirt", 1);
            }

            @Override
            public java.util.Map<String, Integer> travelMaterialNeeds(TaskContext context) {
                return java.util.Map.of("minecraft:dirt", 1);
            }

            @Override
            public String describe() {
                return "testing the return reserve";
            }

            @Override
            public String resultSummary() {
                return "";
            }
        };
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 100, () -> false);
        runner.start(task, "return_reserve_test");
        runner.tick();

        h.assertTrue(companion.materialReserve().workReserved("minecraft:dirt") == 1, "work reserve was lost");
        h.assertTrue(companion.materialReserve().travelReserved("minecraft:dirt") == 1, "travel reserve was lost");
        h.assertTrue(
                companion.materialReserve().forReturnLeg().reserved("minecraft:dirt") == 1,
                "return navigation did not retain the work reserve");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void aVisitThatFindsNothingForgetsTheSite(GameTestHelper h) {
        GameTestSupport.platformAt(h, 500, 32);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 501, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 501, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        dev.friendai.entity.CompanionManager.get(h.getLevel().getServer()).register(companion);
        state.observations().register(companion);
        String dimension = h.getLevel().dimension().identifier().toString();
        var ore = h.absolutePos(new net.minecraft.core.BlockPos(10, 501, 2));
        var viewpoint = h.absolutePos(new net.minecraft.core.BlockPos(2, 501, 2));
        long observed = Math.max(0, h.getLevel().getGameTime() - 1);
        // Only the memory exists: no coal block was placed, so the visit has nothing to see.
        long siteId = state.knowledge()
                .book(owner.getUUID())
                .observe(
                        new ResourceObservation(
                                "minecraft:coal_ore",
                                new ResourceObservation.Location(
                                        dimension, new Pos(ore.getX(), ore.getY(), ore.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                observed),
                        "minecraft:coal_ore");
        h.assertTrue(
                state.knowledge()
                        .book(owner.getUUID())
                        .site(siteId, h.getLevel().getGameTime() + 1)
                        .map(site -> !site.members().isEmpty())
                        .orElse(false),
                "the remembered site should exist before the visit");

        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 600, () -> false);
        runner.setEventSink(result);
        runner.start(new VisitResourceTask(siteId, dimension, 400), "visit_resource");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING, "waiting for the visit to finish");
            h.assertTrue(result.status == TaskStatus.FAILED, "an empty site must fail the visit: " + result.status);
            var site = state.knowledge()
                    .book(owner.getUUID())
                    .site(siteId, h.getLevel().getGameTime() + 1);
            h.assertTrue(
                    site.map(candidate -> candidate.members().isEmpty()).orElse(true),
                    "the site must be forgotten after a visit found nothing: " + site);
        });
    }

    private static final class Result implements TaskEventSink {
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
        var state = GameTestSupport.runtime(h.getLevel().getServer());
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
        var state = GameTestSupport.runtime(h.getLevel().getServer());
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

    @GameTest(maxTicks = 400)
    public void trailOnlyKnowledgeIsWrittenByTheRuntimeFlush(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2).getUUID();
        var server = h.getLevel().getServer();
        var state = GameTestSupport.runtime(server);
        String dimension = h.getLevel().dimension().identifier().toString();
        state.knowledge()
                .book(owner)
                .trails()
                .recordSegment(dimension, java.util.List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)), 20, false);
        h.assertTrue(
                state.knowledge().status(owner).dirty(),
                "a trail-only change must mark the runtime knowledge book dirty");
        var dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("friendai")
                .resolve("knowledge");
        h.succeedWhen(() -> {
            if (state.knowledge().status(owner).dirty()) return;
            try (var reloaded = new dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore(
                    dir, dev.friendai.features.knowledge.KnowledgeLimits.DEFAULTS)) {
                h.assertTrue(
                        !reloaded.book(owner)
                                .trails()
                                .route(dimension, new Pos(0, 64, 0), new Pos(8, 64, 0))
                                .isEmpty(),
                        "the periodic runtime flush persists a trail-only change");
            }
        });
    }

    /**
     * Closes and rebuilds the server runtime to simulate a restart, so it must not share a batch with any other test:
     * every test in its batch would lose the runtime it already holds. Its own test environment gives it its own batch.
     */
    @GameTest(maxTicks = 2400, environment = "friendai-gametest:restart_resource_journey")
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
        var beforeRestart = GameTestSupport.runtime(server);
        beforeRestart.knowledge().flushAll();
        GameTestSupport.closeRuntime(server);
        var afterRestart = GameTestSupport.runtime(server);
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
        runner.setEventSink(result);
        runner.start(new VisitResourceTask(remembered.getFirst().site().id(), dimension, 400), "visit_resource");
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
                                dev.friendai.world.block.BlockMatcher.parse("minecraft:coal_ore")
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
        var state = GameTestSupport.runtime(server);
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
        runner.setEventSink(result);
        runner.start(new VisitResourceTask(remembered.getFirst().site().id(), dimension, 1000), "visit_resource");
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
                                dev.friendai.world.block.BlockMatcher.parse("minecraft:iron_ore")
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
        var state = GameTestSupport.runtime(h.getLevel().getServer());
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
                !state.resourceCandidates().plannerCandidates(companion, 20).isEmpty(),
                "historical visible logs should become a resource candidate");
        var facts = WorldScan.scan(h.getLevel(), companion, 32, false, true);
        var plan = dev.friendai.features.acquisition.ObtainPlanner.plan(
                "minecraft:oak_log",
                2,
                java.util.Map.of(),
                dev.friendai.features.crafting.minecraft.RecipeIndex.of(h.getLevel()),
                DropIndex.of(h.getLevel()),
                facts);
        h.assertTrue(
                !plan.steps().isEmpty()
                        && plan.steps().get(0) instanceof dev.friendai.features.acquisition.ObtainStep.VisitResource,
                "expected remembered visit plan, got "
                        + dev.friendai.features.acquisition.ObtainText.planText(plan, "minecraft:oak_log", 2));

        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 2400, () -> false);
        runner.setEventSink(result);
        var candidate =
                state.resourceCandidates().plannerCandidates(companion, 20).get(0);
        runner.start(new VisitResourceTask(candidate.siteId(), dimension, 400), "visit_resource");
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
