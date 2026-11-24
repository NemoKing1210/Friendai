package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.task.ExploreTaskFactory;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.acquisition.minecraft.task.ResourceSearchDriver;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.SearchPlanner;
import dev.friendai.features.knowledge.search.SearchSession;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.persistence.SavedExecution;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class ResourceSearchTests {
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 40)
    public void miningFailureRecognizesSameTickAdjacentEvidence(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        BlockPos cover = h.absolutePos(new BlockPos(5, 3, 5));
        BlockPos exposed = cover.east();
        h.getLevel().setBlock(cover, Blocks.DIRT.defaultBlockState(), 3);
        h.getLevel().setBlock(exposed, Blocks.STONE.defaultBlockState(), 3);
        String dimension = h.getLevel().dimension().identifier().toString();
        long now = h.getLevel().getServer().overworld().getGameTime();
        BlockPos originBlock = companion.blockPosition();
        Pos origin = new Pos(originBlock.getX(), originBlock.getY(), originBlock.getZ());
        var profile = new ResourceSearchProfile(
                "minecraft:stone", Set.of(dimension), OptionalInt.empty(), Set.of(ResourceSearchProfile.Strategy.MINE));
        var target = new TargetKind.BlockFamily("minecraft:stone", Set.of("minecraft:stone"), profile);
        var session = new SearchSession(
                UUID.randomUUID(),
                dimension,
                "minecraft:stone",
                origin,
                new SearchSession.Limits(32, 128, 2400, 24),
                now);
        ExploreTaskFactory factory =
                (blocks, down, depthY, radius, maxBlocks, maxTicks) -> new ExploreTaskFactory.MiningTask() {
                    private boolean broken;

                    @Override
                    public int excavationBlocks() {
                        return broken ? 1 : 0;
                    }

                    @Override
                    public String type() {
                        return "test_mining_failure_after_break";
                    }

                    @Override
                    public void start(TaskContext context) {}

                    @Override
                    public TaskStatus tick(TaskContext context) {
                        context.level().setBlock(cover, Blocks.AIR.defaultBlockState(), 3);
                        context.resources().ownBreak(companion, cover);
                        broken = true;
                        return TaskStatus.FAILED;
                    }

                    @Override
                    public void stop(TaskContext context) {}

                    @Override
                    public String describe() {
                        return "breaks cover and fails in one tick";
                    }

                    @Override
                    public String resultSummary() {
                        return "the fake mining task failed after breaking the cover";
                    }
                };
        var driver = new ResourceSearchDriver(
                session,
                target,
                profile,
                32,
                false,
                ResourceSearchProfile.Strategy.MINE,
                null,
                null,
                null,
                false,
                factory);
        TaskContext context = new TaskContext(companion, h.getLevel());
        driver.start(context);

        TaskStatus status = driver.tick(context);
        h.assertTrue(
                status == TaskStatus.SUCCESS,
                "fresh face-adjacent evidence must win over a same-tick mining failure: " + driver.resultSummary());
        h.assertTrue(driver.resultSummary().contains("found minecraft:stone"), driver.resultSummary());
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_24, maxTicks = 800)
    public void woodenPickaxeFindsStoneUnderDirtWithoutTorches(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 24);
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 194; y < 199; y++) h.setBlock(x, y, z, Blocks.STONE);
                for (int y = 199; y <= 200; y++) h.setBlock(x, y, z, Blocks.DIRT);
            }
        }
        var owner = GameTestSupport.mockPlayerAt(h, 10, 201, 10);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 10, 201, 10);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.WOODEN_PICKAXE.getDefaultInstance());
        Result result = new Result();
        companion.executionCoordinator().setEventSink(result);
        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:stone", "auto", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            h.assertTrue(result.status != null, "shallow stone search should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("found"), result.summary);
            h.assertTrue(
                    companion.blockPosition().getY()
                            < h.absolutePos(new BlockPos(10, 201, 10)).getY(),
                    "search must excavate beneath the dirt rather than wait on the surface");
            var sessions = GameTestSupport.runtime(h.getLevel().getServer())
                    .knowledge()
                    .book(owner.getUUID())
                    .searchLedger()
                    .snapshot();
            h.assertTrue(
                    sessions.size() == 1 && sessions.getFirst().excavationBlocks() > 0,
                    "successful search must account for excavation in its bounded session");
        });
    }

    /**
     * A family whose first sorted id is absent must still be found through the variant actually present: the child
     * explores for the whole family, not one arbitrary member.
     */
    @GameTest(structure = GameTestArenas.ARENA_24, maxTicks = 800)
    public void theMineChildReceivesTheWholeFamily(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 24);
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 194; y < 199; y++) h.setBlock(x, y, z, Blocks.DEEPSLATE);
                for (int y = 199; y <= 200; y++) h.setBlock(x, y, z, Blocks.DIRT);
            }
        }
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 10, 201, 10);
        companion.setOwner(java.util.UUID.randomUUID());
        companion.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().setItem(1, Items.TORCH.getDefaultInstance().copyWithCount(16));
        String dimension = h.getLevel().dimension().identifier().toString();
        long now = h.getLevel().getServer().overworld().getGameTime();
        BlockPos at = companion.blockPosition();
        // The alphabetically first member is the one this world does not have.
        var target = new TargetKind.BlockFamily(
                "minecraft:iron_ore",
                new java.util.LinkedHashSet<>(java.util.List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore")),
                new ResourceSearchProfile(
                        "minecraft:iron_ore",
                        Set.of(dimension),
                        OptionalInt.of(-20),
                        Set.of(ResourceSearchProfile.Strategy.MINE)));
        var session = new SearchSession(
                java.util.UUID.randomUUID(),
                dimension,
                "minecraft:iron_ore",
                new Pos(at.getX(), at.getY(), at.getZ()),
                new SearchSession.Limits(32, 128, 2_400, 64),
                now);
        String[] started = {null};
        ExploreTaskFactory factory = (blocks, down, depthY, radius, maxBlocks, maxTicks) -> {
            started[0] = String.join(",", new java.util.TreeSet<>(blocks));
            return new ExploreTaskFactory.MiningTask() {
                @Override
                public int excavationBlocks() {
                    return 0;
                }

                @Override
                public String type() {
                    return "test_mining_noop";
                }

                @Override
                public void start(TaskContext context) {}

                @Override
                public TaskStatus tick(TaskContext context) {
                    return TaskStatus.FAILED;
                }

                @Override
                public void stop(TaskContext context) {}

                @Override
                public String describe() {
                    return "records the block set it was asked to find";
                }

                @Override
                public String resultSummary() {
                    return "no block found";
                }
            };
        };
        var driver = new ResourceSearchDriver(
                session,
                target,
                (ResourceSearchProfile) target.profile(),
                32,
                false,
                ResourceSearchProfile.Strategy.MINE,
                null,
                null,
                null,
                false,
                factory);
        driver.start(new TaskContext(companion, h.getLevel()));

        h.assertValueEqual(
                started[0], "minecraft:deepslate_iron_ore,minecraft:iron_ore", "the child receives the whole family");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void theMineTaskRecognizesTheVariantItMeets(GameTestHelper h) {
        GameTestSupport.platformAt(h, 10, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 11, 2);
        BlockPos ore = h.absolutePos(new BlockPos(5, 11, 5));
        h.setBlock(5, 11, 5, Blocks.DEEPSLATE_IRON_ORE);
        h.assertTrue(GameTestSupport.observeVisible(h, companion, ore), "the deepslate variant should be visible");
        TaskContext context = new TaskContext(companion, h.getLevel());
        var factory = new MinecraftObtainSubtaskFactory();

        var family =
                factory.create(Set.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), true, 10, 16, 128, 600);
        family.start(context);
        h.assertTrue(
                family.resultSummary().contains("found minecraft:deepslate_iron_ore"),
                "the family matches the variant present: " + family.resultSummary());

        var oneMember = factory.create("minecraft:iron_ore", true, 10, 16, 128, 600);
        oneMember.start(context);
        h.assertFalse(
                oneMember.resultSummary().contains("found"),
                "a single absent id must not match it: " + oneMember.resultSummary());
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void aMineFailureNamesTheLegReason(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) h.setBlock(x, 1, z, Blocks.BEDROCK);
        }
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.WOODEN_PICKAXE.getDefaultInstance());
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (x == 2 && z == 2) continue;
                h.setBlock(x, 2, z, Blocks.BEDROCK);
                h.setBlock(x, 3, z, Blocks.BEDROCK);
            }
        }
        h.setBlock(2, 4, 2, Blocks.BEDROCK);
        Result result = new Result();
        companion.executionCoordinator().setEventSink(result);
        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:stone", "mine", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            h.assertTrue(result.status != null, "mine failure should finish");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("dug ") || result.summary.contains("why="), result.summary);
        });
    }

    @GameTest(
            structure = GameTestArenas.ARENA_16,
            maxTicks = 40,
            environment = "friendai-gametest:search_trusted_expedition")
    public void trustedCannotEscalateToExpedition(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var config = state.config();
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:coal_ore", "auto", "expedition"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED));
        h.assertTrue(result.error(), "trusted caller must not escalate to expedition scope");
        h.assertTrue(!companion.executionCoordinator().hasWork(), "denied expedition must not start a task");
        h.succeed();
    }

    @GameTest(
            structure = GameTestArenas.ARENA_16,
            maxTicks = 40,
            environment = "friendai-gametest:search_expedition_budget")
    public void ownerExpeditionUsesConfiguredHardBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var config = state.config();
        config.tasks.resources.allowExpeditions = true;
        config.tasks.resources.expeditionRadius = 700;
        config.tasks.resources.expeditionTicks = 18_000;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:coal_ore", "surface", "expedition"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!result.error(), "authorized owner expedition should start");
        var session = state.knowledge().book(owner.getUUID()).searchLedger().snapshot().stream()
                .findFirst()
                .orElse(null);
        h.assertTrue(session != null, "owner request should create a persisted search session");
        h.assertTrue(
                session.limits().radius() == 700 && session.limits().ticks() == 18_000,
                "search must use server policy values, not arbitrary tool overrides");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("search_resource") && task.saveData() != null)
                        .orElse(false),
                "an active search must carry a resumable checkpoint");
        companion.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    /**
     * The session can refuse a candidate the planner offered: the remaining budget must cover the walk out and the
     * walk back. A refused frontier must not start its leg, move the companion or take the leg's chunk lease.
     */
    @GameTest(
            structure = GameTestArenas.ARENA_16,
            maxTicks = 200,
            environment = "friendai-gametest:search_budget_refusal")
    public void aRefusedFrontierNeverStartsItsLeg(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var config = state.config();
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);

        String dimension = h.getLevel().dimension().identifier().toString();
        BlockPos start = companion.blockPosition();
        Pos origin = new Pos(start.getX(), start.getY(), start.getZ());
        var profile = new ResourceSearchProfile(
                "minecraft:coal_ore",
                Set.of(dimension),
                OptionalInt.empty(),
                Set.of(ResourceSearchProfile.Strategy.SURFACE));
        var target = new TargetKind.BlockFamily("minecraft:coal_ore", Set.of("minecraft:coal_ore"), profile);
        // 100 ticks leaves the nearest 16-block candidate inside the planner's own budget filter, while the session
        // still refuses it: walking there and back would cost more than what is left.
        var session = new SearchSession(
                UUID.randomUUID(),
                dimension,
                "minecraft:coal_ore",
                origin,
                new SearchSession.Limits(32, 128, 100, 24),
                h.getLevel().getServer().overworld().getGameTime());
        var horizon = state.chunkLoader().expeditionHorizon();
        var driver = new ResourceSearchDriver(
                session, target, profile, 32, true, ResourceSearchProfile.Strategy.SURFACE, null, null, null);
        TaskContext context = new TaskContext(companion, h.getLevel());
        driver.start(context);
        boolean[] refused = {false};
        String[] prematureDispatch = {null};
        h.succeedWhen(() -> {
            TaskStatus status = driver.tick(context);
            h.assertTrue(
                    !horizon.holdsLease(owner.getUUID()),
                    "a refused frontier must not take its chunk lease: " + driver.resultSummary());
            h.assertTrue(
                    companion.blockPosition().distManhattan(start) < 4,
                    "a refused frontier must not move the companion: " + driver.resultSummary());
            if (!refused[0] && session.snapshot().phase() == SearchSession.Phase.RETURN) {
                refused[0] = true;
                var base = driver.debugWorkSets().getFirst().cells().getFirst();
                if (base.x() != origin.x() || base.y() != origin.y() || base.z() != origin.z()) {
                    prematureDispatch[0] = "the driver named a frontier the session refused: " + base;
                } else if (status == TaskStatus.RUNNING) {
                    prematureDispatch[0] = "the refusal did not take effect on the tick it arrived";
                }
            }
            h.assertTrue(
                    refused[0],
                    "the session must refuse a leg its remaining budget cannot cover: " + driver.resultSummary());
            h.assertTrue(prematureDispatch[0] == null, prematureDispatch[0] + " (" + driver.resultSummary() + ")");
            h.assertTrue(status == TaskStatus.FAILED, "status " + status + " " + driver.resultSummary());
            h.assertTrue(driver.resultSummary().contains("budget"), driver.resultSummary());
        });
    }

    @GameTest(
            structure = GameTestArenas.ARENA_16,
            maxTicks = 160,
            environment = "friendai-gametest:search_restart_expedition")
    public void restartResumesAnOutboundExpeditionAndReacquiresItsLease(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var config = state.config();
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        config.tasks.resources.expeditionRadius = 96;
        config.tasks.resources.expeditionTicks = 6_000;
        state.applyConfig(config);

        var started = GameTestToolRegistry.create(original, () -> 16, MemoryAccess.DISABLED)
                .bind(original)
                .execute(
                        searchCall("minecraft:coal_ore", "surface", "expedition"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        h.assertTrue(
                original.executionCoordinator().current().isPresent(),
                "a search enqueued by its tool must start at once: jobs="
                        + original.executionCoordinator().queue().jobs());
        var horizon = state.chunkLoader().expeditionHorizon();
        var context = new TaskContext(original, h.getLevel());
        var task = original.executionCoordinator().current().orElseThrow();
        for (int i = 0; i < 120 && !horizon.holdsLease(owner.getUUID()); i++) task.tick(context);
        h.assertTrue(horizon.holdsLease(owner.getUUID()), "the outbound expedition should hold its next-leg lease");

        var ledger = state.knowledge().book(owner.getUUID()).searchLedger();
        SearchSession.Snapshot before = ledger.snapshot().getFirst();
        var persistence = state.taskPersistence();
        var captured = persistence.capture(original.executionCoordinator()).orElseThrow();
        // The durable document is written before the restart, exactly as a real save would be.
        String stored = persistence.encode(captured);
        h.assertTrue(
                captured.activeTask() != null
                        && captured.activeTask().type().equals("search_resource")
                        && captured.queue().activeJobId() > 0,
                "an active search must carry a checkpoint that names its session; activeJob="
                        + captured.queue().activeJobId() + " task="
                        + (captured.activeTask() == null
                                ? "none"
                                : captured.activeTask().type()) + " jobs="
                        + captured.queue().queue().jobs());
        ledger.interruptActive();
        original.executionCoordinator().stop("simulated restart");
        original.discard();
        h.assertTrue(!horizon.holdsLease(owner.getUUID()), "a restart must not leave a stale lease behind");

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        restored.setOwner(owner.getUUID());
        var saved = persistence.decode(stored).orElseThrow();
        var report = persistence.restore(restored, saved);
        var job = restored.executionCoordinator()
                .queue()
                .job(saved.queue().activeJobId())
                .orElseThrow();
        h.assertTrue(
                job.state() == dev.friendai.execution.queue.JobState.RUNNING,
                "a recoverable search job must stay running after a restart");
        var resumed = restored.executionCoordinator().current().orElseThrow();
        h.assertTrue(
                !horizon.holdsLease(owner.getUUID()), "the resumed search must not hold a lease before its first leg");
        SearchSession.Snapshot after = ledger.snapshot().getFirst();
        h.assertTrue(after.id().equals(before.id()), "the same ledger session must continue");
        h.assertTrue(
                after.spentTicks() == before.spentTicks() && after.spentTravelBlocks() == before.spentTravelBlocks(),
                "a restart must neither reset nor double-charge the spent search budget");
        h.assertTrue(
                report.restored().stream().anyMatch(line -> line.contains("resumed after restart")),
                report.restored().toString());

        var resumedContext = new TaskContext(restored, h.getLevel());
        resumed.tick(resumedContext);
        h.assertTrue(resumed.resultSummary().contains("resumed after restart"), resumed.resultSummary());
        for (int i = 0; i < 120 && !horizon.holdsLease(owner.getUUID()); i++) resumed.tick(resumedContext);
        h.assertTrue(
                horizon.holdsLease(owner.getUUID()), "the resumed expedition must reacquire a lease for its next leg");

        restored.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 120)
    public void restartResumesAnInspectionWithOnlyItsRemainingExcavation(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner.getUUID());
        original.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());
        original.inventory().setItem(1, Items.TORCH.getDefaultInstance().copyWithCount(16));
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var started = GameTestToolRegistry.create(original, () -> 16, MemoryAccess.DISABLED)
                .bind(original)
                .execute(
                        searchCall("minecraft:iron_ore", "mine", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        original.executionCoordinator().tick();

        var ledger = state.knowledge().book(owner.getUUID()).searchLedger();
        SearchSession.Snapshot snapshot = ledger.snapshot().getFirst();
        SearchSession live = ledger.session(snapshot.id());
        long now = h.getLevel().getServer().overworld().getGameTime();
        var at = original.blockPosition();
        Pos here = new Pos(at.getX(), at.getY(), at.getZ());
        String dimension = h.getLevel().dimension().identifier().toString();
        var frontier = new SearchPlanner.Frontier(here, here, ResourceSearchProfile.Strategy.MINE, 1, 2);
        // Two advances with a valid frontier reach TRAVEL from either PREPARE or CHOOSE_FRONTIER.
        live.advance(new SearchSession.Input(
                now + 1, dimension, here, true, false, SearchSession.LegOutcome.NONE, frontier, 0, 0, 0, 0));
        live.advance(new SearchSession.Input(
                now + 2, dimension, here, true, false, SearchSession.LegOutcome.NONE, frontier, 0, 0, 0, 0));
        h.assertTrue(
                live.snapshot().phase() == SearchSession.Phase.TRAVEL,
                "phase " + live.snapshot().phase());
        live.accountWork(now + 3, here, 0, 5);
        live.advance(new SearchSession.Input(
                now + 4, dimension, here, true, false, SearchSession.LegOutcome.ARRIVED, null, 0, 0, 0, 0));
        h.assertTrue(
                live.snapshot().phase() == SearchSession.Phase.INSPECT,
                "the fixture should be inspecting after five excavated blocks: "
                        + live.snapshot().phase());

        var persistence = state.taskPersistence();
        var captured = persistence.capture(original.executionCoordinator()).orElseThrow();
        String stored = persistence.encode(captured);
        h.assertTrue(
                captured.activeTask() != null && captured.queue().activeJobId() > 0,
                "a mining search must carry a checkpoint: activeJob="
                        + captured.queue().activeJobId() + " task="
                        + (captured.activeTask() == null
                                ? "none"
                                : captured.activeTask().type()) + " jobs="
                        + captured.queue().queue().jobs());
        ledger.interruptActive();
        original.executionCoordinator().stop("simulated restart");
        original.discard();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        restored.setOwner(owner.getUUID());
        restored.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());
        restored.inventory().setItem(1, Items.TORCH.getDefaultInstance().copyWithCount(16));
        var report = persistence.restore(restored, persistence.decode(stored).orElseThrow());
        var resumed = restored.executionCoordinator().current().orElseThrow();
        h.assertTrue(
                report.restored().stream().anyMatch(line -> line.contains("resumed after restart")),
                report.restored().toString());
        h.assertTrue(
                ledger.snapshot().getFirst().excavationBlocks() >= 5,
                "the resumed search must keep the five blocks it already excavated");
        resumed.tick(new TaskContext(restored, h.getLevel()));
        String summary = resumed.resultSummary();
        h.assertTrue(summary.contains("resumed after restart"), summary);
        h.assertTrue(!summary.contains("excavation=0/"), "a restart must not replay the mining leg: " + summary);
        h.assertTrue(
                ledger.snapshot().getFirst().excavationBlocks()
                        < ledger.snapshot().getFirst().limits().excavationBlocks(),
                "the resumed mining leg must stay inside the remaining excavation budget");
        restored.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 80)
    public void restartFinishesAReturnWalkWithItsDiscovery(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner.getUUID());
        var started = GameTestToolRegistry.create(original, () -> 16, MemoryAccess.DISABLED)
                .bind(original)
                .execute(
                        searchCall("minecraft:coal_ore", "surface", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        original.executionCoordinator().tick();

        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var ledger = state.knowledge().book(owner.getUUID()).searchLedger();
        SearchSession.Snapshot snapshot = ledger.snapshot().getFirst();
        SearchSession live = ledger.session(snapshot.id());
        long now = h.getLevel().getServer().overworld().getGameTime();
        var at = original.blockPosition();
        Pos here = new Pos(at.getX(), at.getY(), at.getZ());
        live.advance(new SearchSession.Input(
                now + 1,
                h.getLevel().dimension().identifier().toString(),
                here,
                true,
                true,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        h.assertTrue(live.snapshot().phase() == SearchSession.Phase.RETURN, "the trip should be walking home");
        h.assertTrue(live.snapshot().discovered(), "the discovery must be recorded before the restart");

        var persistence = state.taskPersistence();
        String stored = persistence.encode(
                persistence.capture(original.executionCoordinator()).orElseThrow());
        ledger.interruptActive();
        original.executionCoordinator().stop("simulated restart");
        original.discard();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        restored.setOwner(owner.getUUID());
        persistence.restore(restored, persistence.decode(stored).orElseThrow());
        restored.executionCoordinator().current().orElseThrow();
        // Restoring attaches the owner's session sink first; the test sink replaces it afterwards.
        Result result = new Result();
        restored.executionCoordinator().setEventSink(result);

        for (int i = 0; i < 10 && result.status == null; i++)
            restored.executionCoordinator().tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
        h.assertTrue(result.summary.contains("returned to origin"), result.summary);
        h.assertTrue(result.summary.contains("found"), result.summary);
        h.assertTrue(
                ledger.snapshot().getFirst().phase() == SearchSession.Phase.COMPLETE,
                "the resumed walk home must complete the same session");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
    public void aCheckpointlessRetryFromANewBaseStartsItsOwnSearch(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner.getUUID());
        var started = GameTestToolRegistry.create(original, () -> 16, MemoryAccess.DISABLED)
                .bind(original)
                .execute(
                        searchCall("minecraft:coal_ore", "surface", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        original.executionCoordinator().tick();

        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var ledger = state.knowledge().book(owner.getUUID()).searchLedger();
        SearchSession.Snapshot before = ledger.snapshot().getFirst();
        var persistence = state.taskPersistence();
        var captured = persistence
                .decode(persistence.encode(
                        persistence.capture(original.executionCoordinator()).orElseThrow()))
                .orElseThrow();
        // A save written before the checkpoint existed: the job args are all the restart has to rebuild from.
        var legacy = new SavedExecution(
                SavedExecution.CURRENT_VERSION,
                new QueueSave.Decoded(captured.queue().queue(), captured.queue().activeJobId()),
                null);
        ledger.interruptActive();
        original.executionCoordinator().stop("simulated restart");
        original.discard();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        restored.setOwner(owner.getUUID());
        var report = persistence.restore(restored, legacy);
        var job = restored.executionCoordinator()
                .queue()
                .job(captured.queue().activeJobId())
                .orElseThrow();
        h.assertTrue(
                job.state() == dev.friendai.execution.queue.JobState.RUNNING,
                "a search job without a checkpoint must be retried, not cancelled");
        var resumed = restored.executionCoordinator().current().orElseThrow();
        // Without a checkpoint the retry starts from wherever the companion is now. That is a different origin,
        // so it must not borrow the interrupted trip's budget and must not inherit its phase.
        var retrySession = ledger.snapshot().stream()
                .filter(session -> !session.id().equals(before.id()))
                .findFirst();
        h.assertTrue(retrySession.isPresent(), "the retry did not start its own session: " + ledger.snapshot());
        SearchSession.Snapshot after = retrySession.orElse(before);
        var at = restored.blockPosition();
        h.assertTrue(
                after.origin().equals(new Pos(at.getX(), at.getY(), at.getZ())),
                "a new leg must use one origin: " + after.origin() + " at " + at);
        h.assertTrue(after.phase() != SearchSession.Phase.INTERRUPTED, "the new search must not inherit a phase");
        SearchSession.Snapshot kept = ledger.snapshot().stream()
                .filter(session -> session.id().equals(before.id()))
                .findFirst()
                .orElse(before);
        h.assertTrue(
                kept.spentTicks() >= before.spentTicks(),
                "the interrupted trip keeps its own spent budget: before=" + before.spentTicks() + " after="
                        + kept.spentTicks());
        h.assertTrue(
                report.restored().stream().anyMatch(line -> line.contains("retried after restart")),
                report.restored().toString());
        resumed.tick(new TaskContext(restored, h.getLevel()));
        h.assertTrue(
                !resumed.resultSummary().contains("resumed after restart"),
                "the factory retry is not an exact resume: " + resumed.resultSummary());
        restored.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    @GameTest(
            structure = GameTestArenas.ARENA_16,
            maxTicks = 40,
            environment = "friendai-gametest:search_stop_expedition")
    public void stoppingExpeditionReleasesItsNextLegTicket(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var config = state.config();
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);
        GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:coal_ore", "surface", "expedition"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        var task = companion.executionCoordinator().current().orElseThrow();
        var context = new dev.friendai.task.TaskContext(companion, h.getLevel());
        var horizon = state.chunkLoader().expeditionHorizon();
        for (int i = 0; i < 50 && !horizon.holdsLease(owner.getUUID()); i++) {
            task.tick(context);
        }
        h.assertTrue(
                horizon.holdsLease(owner.getUUID()),
                "an outbound expedition leg should hold exactly its next-leg ticket");

        companion.executionCoordinator().stop("test cancellation");
        h.assertTrue(
                !horizon.holdsLease(owner.getUUID()),
                "stopping an expedition must release its next-leg ticket immediately");
        h.assertTrue(companion.getNavigation().isDone(), "stopping an expedition must leave no active navigator path");
        h.assertTrue(
                state.knowledge().book(owner.getUUID()).searchLedger().snapshot().stream()
                        .allMatch(session -> session.phase() == SearchSession.Phase.INTERRUPTED),
                "a stopped search must leave its persistent ledger interrupted while preserving spent work");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 40)
    public void ownerMineStrategyStartsBoundedMiningSearch(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().setItem(1, Items.TORCH.getDefaultInstance().copyWithCount(16));

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:iron_ore", "mine", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "owner mine search should be accepted when equipped");
        SearchSession.Snapshot session =
                GameTestSupport.runtime(h.getLevel().getServer())
                        .knowledge()
                        .book(owner.getUUID())
                        .searchLedger()
                        .snapshot()
                        .stream()
                        .findFirst()
                        .orElse(null);
        h.assertTrue(session != null, "mine search should acquire a persistent bounded session");
        h.assertTrue(
                session.limits().radius() == 64
                        && session.limits().travelBlocks() == 256
                        && session.limits().ticks() == 2_400
                        && session.limits().excavationBlocks() == 128,
                "local mining strategy must use the bounded default search budget");
        h.assertTrue(
                !companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.resultSummary())
                        .orElse("")
                        .contains("no observed entrance or safe local route"),
                "permitted MINE strategy must start its guarded mining driver");
        companion.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 40)
    public void miningSearchRejectsInsufficientEquipmentBeforeAcquiringBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:iron_ore", "mine", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "tool call should start a task that can report its readiness reason");
        h.assertTrue(
                GameTestSupport.runtime(h.getLevel().getServer())
                        .knowledge()
                        .book(owner.getUUID())
                        .searchLedger()
                        .snapshot()
                        .isEmpty(),
                "readiness failure must not acquire or consume a session");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.resultSummary().contains("16 torches"))
                        .orElse(false),
                "mining readiness failure must report its torch requirement");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 40)
    public void lowFoodRejectsSearchBeforeAcquiringBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.hunger().load(5, 0.0F, 0.0F);

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:coal_ore", "surface", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "tool call should start a task that can report its readiness reason");
        h.assertTrue(
                GameTestSupport.runtime(h.getLevel().getServer())
                        .knowledge()
                        .book(owner.getUUID())
                        .searchLedger()
                        .snapshot()
                        .isEmpty(),
                "readiness failure must not acquire or consume a session");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.resultSummary().contains("need food"))
                        .orElse(false),
                "low food failure must be reported before departure");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 40)
    public void caveStrategyDoesNotAcquireBudgetWithoutObservedEntrance(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:coal_ore", "cave", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "a bounded cave request should report its unavailable entrance as task status");
        h.assertTrue(
                state.knowledge()
                        .book(owner.getUUID())
                        .searchLedger()
                        .snapshot()
                        .isEmpty(),
                "a cave search without observed entrance evidence must not acquire or spend a session");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.resultSummary().contains("no directly observed cave entrance"))
                        .orElse(false),
                "the task should explain that no directly observed entrance is available");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 40)
    public void caveStrategyStartsFromAnObservedEntrance(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        String dimension = h.getLevel().dimension().identifier().toString();
        long now = h.getLevel().getServer().overworld().getGameTime();
        state.resourceCandidates()
                .observeCaveEntrance(
                        companion,
                        new CaveEntranceObservation(
                                dimension, new Pos(3, 3, 2), new Pos(4, 2, 2), new Pos(5, 1, 2), now));

        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:coal_ore", "cave", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "an owner search with observed entrance evidence should start");
        SearchSession.Snapshot session = state.knowledge().book(owner.getUUID()).searchLedger().snapshot().stream()
                .findFirst()
                .orElse(null);
        h.assertTrue(session != null, "a permitted observed cave entrance should acquire a bounded session");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("search_resource") && task.saveData() != null)
                        .orElse(false),
                "the active cave search must carry a resumable checkpoint");
        companion.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 80)
    public void unsupportedFamilyIsReportedNotCrashed(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        companion.executionCoordinator().setEventSink(result);

        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("minecraft:gravel", "auto", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        // The family is rejected before a task exists, so nothing starts and nothing has to fail later.
        h.assertTrue(started.error(), "the unsupported family must be refused: " + started.content());
        h.assertTrue(started.content().contains("no search strategy"), started.content());
        h.assertTrue(result.status == null, "no task should have started, got " + result.status);
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 120)
    public void searchDoesNotTravelWhenWoodIsVisible(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var level = h.getLevel();
        BlockPos start = companion.blockPosition();
        h.setBlock(6, 2, 2, Blocks.ACACIA_LOG);
        BlockPos log = h.absolutePos(new BlockPos(6, 2, 2));
        var state = GameTestSupport.runtime(level.getServer());
        state.resourceCandidates()
                .observe(
                        companion,
                        new ResourceObservation(
                                "minecraft:acacia_log",
                                new ResourceObservation.Location(
                                        level.dimension().identifier().toString(),
                                        new Pos(log.getX(), log.getY(), log.getZ())),
                                new Pos(start.getX(), start.getY(), start.getZ()),
                                level.getServer().overworld().getGameTime()));
        Result result = new Result();
        companion.executionCoordinator().setEventSink(result);

        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        searchCall("#minecraft:logs", "auto", "local"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            if (result.status == null) {
                h.assertTrue(
                        companion.blockPosition().distManhattan(start) < 4,
                        "search should inspect locally before travelling");
                h.assertTrue(false, "search should finish locally");
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("found"), result.summary);
            h.assertTrue(companion.blockPosition().distManhattan(start) < 4, "search travelled for a visible log");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_32, maxTicks = 2_400)
    public void failedRememberedRouteKeepsEvidenceAndObtainUsesSecondSite(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.WOODEN_PICKAXE.getDefaultInstance());
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                h.setBlock(x, 1, z, Blocks.BEDROCK);
                h.setBlock(x, 5, z, Blocks.BEDROCK);
            }
        }
        for (int y = 2; y <= 4; y++) {
            for (int edge = 4; edge <= 8; edge++) {
                h.setBlock(4, y, edge, Blocks.BEDROCK);
                h.setBlock(8, y, edge, Blocks.BEDROCK);
                h.setBlock(edge, y, 4, Blocks.BEDROCK);
                h.setBlock(edge, y, 8, Blocks.BEDROCK);
            }
        }
        for (int x = 5; x <= 7; x++) {
            for (int y = 2; y <= 3; y++) h.setBlock(x, y, 6, Blocks.STONE);
            for (int y = 2; y <= 3; y++) h.setBlock(x + 20, y, 2, Blocks.STONE);
        }
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var book = runtime.knowledge().book(owner.getUUID());
        String dimension = h.getLevel().dimension().identifier().toString();
        BlockPos approachA = h.absolutePos(new BlockPos(6, 2, 5));
        BlockPos approachB = h.absolutePos(new BlockPos(23, 2, 2));
        long observedTick = h.getLevel().getServer().overworld().getGameTime();
        long firstSite = -1;
        long secondSite = -1;
        for (int x = 5; x <= 7; x++) {
            for (int y = 2; y <= 3; y++) {
                BlockPos memberA = h.absolutePos(new BlockPos(x, y, 6));
                ResourceObservation first = new ResourceObservation(
                        "minecraft:stone",
                        new ResourceObservation.Location(
                                dimension, new Pos(memberA.getX(), memberA.getY(), memberA.getZ())),
                        new Pos(approachA.getX(), approachA.getY(), approachA.getZ()),
                        observedTick);
                long id = book.observe(first, first.blockId());
                runtime.resourceCandidates().observe(companion, first);
                firstSite = firstSite < 0 ? id : firstSite;

                BlockPos memberB = h.absolutePos(new BlockPos(x + 20, y, 2));
                ResourceObservation second = new ResourceObservation(
                        "minecraft:stone",
                        new ResourceObservation.Location(
                                dimension, new Pos(memberB.getX(), memberB.getY(), memberB.getZ())),
                        new Pos(approachB.getX(), approachB.getY(), approachB.getZ()),
                        observedTick);
                id = book.observe(second, second.blockId());
                runtime.resourceCandidates().observe(companion, second);
                secondSite = secondSite < 0 ? id : secondSite;
            }
        }
        long failedSite = firstSite;
        long alternateSite = secondSite;
        Result result = new Result();
        var runner = companion.executionCoordinator();
        runner.setEventSink(result);
        long startTick = h.getLevel().getServer().overworld().getGameTime();
        boolean[] started = {false};

        h.succeedWhen(() -> {
            if (!started[0]) {
                if (h.getLevel().getServer().overworld().getGameTime() - startTick < 2) return;
                runner.start(
                        new ObtainTask(
                                "minecraft:cobblestone",
                                6,
                                false,
                                true,
                                20,
                                2_200,
                                32,
                                64,
                                600,
                                "local",
                                null,
                                null,
                                new MinecraftObtainSubtaskFactory()),
                        "obtain_item");
                started[0] = true;
            }
            if (result.status == null) runner.tick();
            h.assertTrue(result.status != null, "obtain still running at " + companion.blockPosition());
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary);
            h.assertTrue(result.summary.contains("visited site " + alternateSite), result.summary);
            h.assertTrue(result.summary.contains("mined 6 minecraft:stone"), result.summary);
            h.assertTrue(
                    book.site(failedSite, h.getLevel().getServer().overworld().getGameTime())
                            .isPresent(),
                    "a failed approach must keep the first site's evidence");
            h.assertTrue(
                    java.util.stream.IntStream.rangeClosed(25, 27)
                            .allMatch(x -> java.util.stream.IntStream.rangeClosed(2, 3)
                                    .allMatch(y -> h.getLevel()
                                            .getBlockState(h.absolutePos(new BlockPos(x, y, 2)))
                                            .isAir())),
                    "the selected second site should be mined after its visit");
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 6, "verified stone output");
        });
    }

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

    private static ToolCall searchCall(String resource, String strategy, String scope) {
        JsonObject args = new JsonObject();
        args.addProperty("resource", resource);
        args.addProperty("strategy", strategy);
        args.addProperty("scope", scope);
        return new ToolCall("test", "search_resource", args, args.toString(), null);
    }
}
