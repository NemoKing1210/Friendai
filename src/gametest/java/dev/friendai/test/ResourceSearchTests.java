package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.knowledge.ResourceObservation;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.tool.builtin.MemoryAccess;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class ResourceSearchTests {
    @GameTest(maxTicks = 800)
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
        companion.taskRunner().setListener(result);
        var started = tools().bind(companion).execute(searchCall("minecraft:stone", "auto", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            h.assertTrue(result.status != null, "shallow stone search should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("found"), result.summary);
            h.assertTrue(companion.blockPosition().getY() < h.absolutePos(new BlockPos(10, 201, 10)).getY(),
                    "search must excavate beneath the dirt rather than wait on the surface");
            var sessions = FriendaiServerState.get(h.getLevel().getServer()).knowledge().book(owner.getUUID())
                    .searchLedger().snapshot();
            h.assertTrue(sessions.size() == 1 && sessions.getFirst().excavationBlocks() > 0,
                    "successful search must account for excavation in its bounded session");
        });
    }

    @GameTest(maxTicks = 40)
    public void trustedCannotEscalateToExpedition(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var config = state.config();
        boolean oldExpeditions = config.tasks.resources.allowExpeditions;
        boolean oldChunkLoading = config.companion.chunkLoading.enabled;
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);

        var result = tools().bind(companion).execute(searchCall("minecraft:coal_ore", "auto", "expedition"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED));
        h.assertTrue(result.error(), "trusted caller must not escalate to expedition scope");
        h.assertTrue(!companion.taskRunner().hasWork(), "denied expedition must not start a task");
        config.tasks.resources.allowExpeditions = oldExpeditions;
        config.companion.chunkLoading.enabled = oldChunkLoading;
        state.applyConfig(config);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void ownerExpeditionUsesConfiguredHardBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var config = state.config();
        boolean oldExpeditions = config.tasks.resources.allowExpeditions;
        int oldRadius = config.tasks.resources.expeditionRadius;
        int oldTicks = config.tasks.resources.expeditionTicks;
        boolean oldChunkLoading = config.companion.chunkLoading.enabled;
        config.tasks.resources.allowExpeditions = true;
        config.tasks.resources.expeditionRadius = 700;
        config.tasks.resources.expeditionTicks = 18_000;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);

        var result = tools().bind(companion).execute(searchCall("minecraft:coal_ore", "surface", "expedition"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!result.error(), "authorized owner expedition should start");
        var session = state.knowledge().book(owner.getUUID()).searchLedger().snapshot().stream().findFirst().orElse(null);
        h.assertTrue(session != null, "owner request should create a persisted search session");
        h.assertTrue(session.limits().radius() == 700 && session.limits().ticks() == 18_000,
                "search must use server policy values, not arbitrary tool overrides");
        h.assertTrue(companion.taskRunner().current().map(task -> task.saveData().startsWith("resource_session:v1:"))
                .orElse(false), "active search should carry a restart interruption marker");
        companion.taskRunner().stop("test cleanup");
        config.tasks.resources.allowExpeditions = oldExpeditions;
        config.tasks.resources.expeditionRadius = oldRadius;
        config.tasks.resources.expeditionTicks = oldTicks;
        config.companion.chunkLoading.enabled = oldChunkLoading;
        state.applyConfig(config);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void stoppingExpeditionReleasesItsNextLegTicket(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var config = state.config();
        boolean oldExpeditions = config.tasks.resources.allowExpeditions;
        boolean oldChunkLoading = config.companion.chunkLoading.enabled;
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);
        tools().bind(companion).execute(searchCall("minecraft:coal_ore", "surface", "expedition"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        var task = companion.taskRunner().current().orElseThrow();
        var context = new dev.friendai.task.TaskContext(companion, h.getLevel());
        for (int i = 0; i < 50 && state.chunkLoader().expeditionHorizon().activeLeases() == 0; i++) {
            task.tick(context);
        }
        h.assertTrue(state.chunkLoader().expeditionHorizon().activeLeases() == 1,
                "an outbound expedition leg should hold exactly its next-leg ticket");

        companion.taskRunner().stop("test cancellation");
        h.assertTrue(state.chunkLoader().expeditionHorizon().activeLeases() == 0
                        && state.chunkLoader().expeditionHorizon().ticketKeys() == 0,
                "stopping an expedition must release its next-leg ticket immediately");
        h.assertTrue(companion.getNavigation().isDone(),
                "stopping an expedition must leave no active navigator path");
        h.assertTrue(state.knowledge().book(owner.getUUID()).searchLedger().snapshot().stream()
                        .allMatch(session -> session.phase() == SearchSession.Phase.INTERRUPTED),
                "a stopped search must leave its persistent ledger interrupted while preserving spent work");
        config.tasks.resources.allowExpeditions = oldExpeditions;
        config.companion.chunkLoading.enabled = oldChunkLoading;
        state.applyConfig(config);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void ownerMineStrategyStartsBoundedMiningSearch(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().setItem(1, Items.TORCH.getDefaultInstance().copyWithCount(16));

        var result = tools().bind(companion).execute(searchCall("minecraft:iron_ore", "mine", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "owner mine search should be accepted when equipped");
        SearchSession.Snapshot session = FriendaiServerState.get(h.getLevel().getServer()).knowledge().book(owner.getUUID())
                .searchLedger().snapshot().stream().findFirst().orElse(null);
        h.assertTrue(session != null, "mine search should acquire a persistent bounded session");
        h.assertTrue(session.limits().radius() == 64 && session.limits().travelBlocks() == 256
                        && session.limits().ticks() == 2_400 && session.limits().excavationBlocks() == 128,
                "local mining strategy must use the bounded default search budget");
        h.assertTrue(!companion.taskRunner().current().map(task -> task.resultSummary())
                .orElse("").contains("no observed entrance or safe local route"),
                "permitted MINE strategy must start its guarded mining driver");
        companion.taskRunner().stop("test cleanup");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void miningSearchRejectsInsufficientEquipmentBeforeAcquiringBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, Items.IRON_PICKAXE.getDefaultInstance());

        var result = tools().bind(companion).execute(searchCall("minecraft:iron_ore", "mine", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "tool call should start a task that can report its readiness reason");
        h.assertTrue(FriendaiServerState.get(h.getLevel().getServer()).knowledge().book(owner.getUUID())
                .searchLedger().snapshot().isEmpty(), "readiness failure must not acquire or consume a session");
        h.assertTrue(companion.taskRunner().current().map(task -> task.resultSummary().contains("16 torches"))
                .orElse(false), "mining readiness failure must report its torch requirement");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void lowFoodRejectsSearchBeforeAcquiringBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.hunger().load(5, 0.0F, 0.0F);

        var result = tools().bind(companion).execute(searchCall("minecraft:coal_ore", "surface", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "tool call should start a task that can report its readiness reason");
        h.assertTrue(FriendaiServerState.get(h.getLevel().getServer()).knowledge().book(owner.getUUID())
                .searchLedger().snapshot().isEmpty(), "readiness failure must not acquire or consume a session");
        h.assertTrue(companion.taskRunner().current().map(task -> task.resultSummary().contains("need food"))
                .orElse(false), "low food failure must be reported before departure");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void caveStrategyDoesNotAcquireBudgetWithoutObservedEntrance(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = FriendaiServerState.get(h.getLevel().getServer());

        var result = tools().bind(companion).execute(searchCall("minecraft:coal_ore", "cave", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "a bounded cave request should report its unavailable entrance as task status");
        h.assertTrue(state.knowledge().book(owner.getUUID()).searchLedger().snapshot().isEmpty(),
                "a cave search without observed entrance evidence must not acquire or spend a session");
        h.assertTrue(companion.taskRunner().current().map(task -> task.resultSummary().contains("no directly observed cave entrance"))
                .orElse(false), "the task should explain that no directly observed entrance is available");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void caveStrategyStartsFromAnObservedEntrance(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var state = FriendaiServerState.get(h.getLevel().getServer());
        String dimension = h.getLevel().dimension().identifier().toString();
        long now = h.getLevel().getServer().overworld().getGameTime();
        state.resources().observeCaveEntrance(companion, new CaveEntranceObservation(dimension,
                new Pos(3, 3, 2), new Pos(4, 2, 2), new Pos(5, 1, 2), now));

        var result = tools().bind(companion).execute(searchCall("minecraft:coal_ore", "cave", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!result.error(), "an owner search with observed entrance evidence should start");
        SearchSession.Snapshot session = state.knowledge().book(owner.getUUID()).searchLedger().snapshot()
                .stream().findFirst().orElse(null);
        h.assertTrue(session != null, "a permitted observed cave entrance should acquire a bounded session");
        h.assertTrue(companion.taskRunner().current().map(task -> task.saveData().startsWith("resource_session:v1:"))
                .orElse(false), "the active cave search should carry the restart interruption marker");
        companion.taskRunner().stop("test cleanup");
        h.succeed();
    }

    @GameTest(maxTicks = 80)
    public void unsupportedFamilyIsReportedNotCrashed(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        companion.taskRunner().setListener(result);

        var started = tools().bind(companion).execute(searchCall("minecraft:gravel", "auto", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("no search strategy"), result.summary);
        });
    }

    @GameTest(maxTicks = 120)
    public void searchDoesNotTravelWhenWoodIsVisible(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var level = h.getLevel();
        BlockPos start = companion.blockPosition();
        h.setBlock(6, 2, 2, Blocks.ACACIA_LOG);
        BlockPos log = h.absolutePos(new BlockPos(6, 2, 2));
        var state = FriendaiServerState.get(level.getServer());
        state.resources().observe(companion, new ResourceObservation("minecraft:acacia_log",
                new ResourceObservation.Location(level.dimension().identifier().toString(),
                        new Pos(log.getX(), log.getY(), log.getZ())),
                new Pos(start.getX(), start.getY(), start.getZ()),
                level.getServer().overworld().getGameTime()));
        Result result = new Result();
        companion.taskRunner().setListener(result);

        var started = tools().bind(companion).execute(searchCall("#minecraft:logs", "auto", "local"),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));

        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            if (result.status == null) {
                h.assertTrue(companion.blockPosition().distManhattan(start) < 4,
                        "search should inspect locally before travelling");
                h.assertTrue(false, "search should finish locally");
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS,
                    "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("found"), result.summary);
            h.assertTrue(companion.blockPosition().distManhattan(start) < 4, "search travelled for a visible log");
        });
    }

    private static final class Result implements TaskListener {
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

    private static ToolRegistry<CompanionEntity> tools() {
        return FriendaiTools.create(() -> 16, MemoryAccess.DISABLED);
    }

    private static ToolCall searchCall(String resource, String strategy, String scope) {
        JsonObject args = new JsonObject();
        args.addProperty("resource", resource);
        args.addProperty("strategy", strategy);
        args.addProperty("scope", scope);
        return new ToolCall("test", "search_resource", args, args.toString(), null);
    }
}
