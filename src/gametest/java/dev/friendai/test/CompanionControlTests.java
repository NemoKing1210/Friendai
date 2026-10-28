package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.RequestType;
import dev.friendai.debug.SnapshotCodec;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.history.TaskRecord;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.net.CompanionRequestC2SPayload;
import dev.friendai.net.CompanionRequestHandler;
import dev.friendai.net.DebugStream;
import dev.friendai.net.Operators;
import dev.friendai.net.SnapshotBuilder;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class CompanionControlTests {
    @GameTest(maxTicks = 200)
    public void teleportDoesNotReturnToTheCancelledWorkSite(GameTestHelper h) {
        int floor = 50;
        GameTestSupport.platformAt(h, floor, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, floor + 1, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        Vec3 workSite = h.absoluteVec(new Vec3(12.5, floor + 1, 12.5));
        companion.teleportTo(workSite.x, workSite.y, workSite.z);
        Task cleanup = new GoToTask(workSite);
        boolean[] stopped = {false};
        companion
                .executionCoordinator()
                .start(
                        new Task() {
                            @Override
                            public String type() {
                                return "work_with_cleanup";
                            }

                            @Override
                            public void start(TaskContext c) {}

                            @Override
                            public TaskStatus tick(TaskContext c) {
                                return TaskStatus.RUNNING;
                            }

                            @Override
                            public void stop(TaskContext c) {
                                stopped[0] = true;
                            }

                            @Override
                            public String describe() {
                                return "working at the old site";
                            }

                            @Override
                            public String resultSummary() {
                                return "";
                            }

                            @Override
                            public Task cleanupAfterCancel(TaskContext c) {
                                return cleanup;
                            }
                        },
                        "work_with_cleanup");
        CompanionRequestHandler.setLimiter(new RateLimiter(0, System::currentTimeMillis));
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.TELEPORT));
        h.assertTrue(stopped[0], "teleport must stop the old task");
        h.assertTrue(companion.position().distanceTo(player.position()) <= 4.0, "not teleported to the owner");
        h.assertTrue(
                companion.executionCoordinator().current().orElse(null) != cleanup,
                "teleport must not send the companion back to clean up the old work site");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void teleportAcrossDimensionsKeepsOneCompanion(GameTestHelper h) {
        var nether = h.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            // Some GameTest configurations have only the overworld.
            h.succeed();
            return;
        }
        int floor = 40;
        GameTestSupport.platformAt(h, floor, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, floor + 1, 2);
        BlockPos far = h.absolutePos(new BlockPos(12, floor + 1, 12));
        nether.getChunk(far);
        CompanionEntity original = FriendaiEntities.COMPANION.create(nether, EntitySpawnReason.COMMAND);
        original.setOwner(player.getUUID());
        original.snapTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5, 0, 0);
        nether.addFreshEntity(original);
        var manager = CompanionManager.get(h.getLevel().getServer());
        manager.register(original);
        Vec3 before = original.position();
        h.assertTrue(
                manager.summon(player) == original
                        && original.level() == nether
                        && original.position().distanceTo(before) < 0.01,
                "summon must leave a companion in another dimension in place");
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        state.sessions().currentPrompt(player.getUUID()); // Create entity-bound tools before the replacement.
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.TELEPORT));
        CompanionEntity moved = manager.find(player.getUUID()).orElse(null);
        h.assertTrue(
                moved != null && moved.level() == h.getLevel() && !moved.isRemoved(),
                "teleport must keep a live companion registered in the owner's dimension");
        h.assertTrue(moved.position().distanceTo(player.position()) <= 4.0, "not teleported to the owner");
        h.assertTrue(original.isRemoved(), "old entity must be removed after changing dimensions");
        h.assertTrue(
                manager.all().stream()
                                .filter(c -> player.getUUID().equals(c.ownerUuid()))
                                .count()
                        == 1,
                "exactly one companion must remain registered");
        state.sessions().runTool(player.getUUID(), "stay", "{}");
        h.assertTrue(
                moved.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("stay"))
                        .orElse(false),
                "brain tools must bind to the replacement companion");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void teleportMenuActionMovesTheCompanion(GameTestHelper h) {
        int floor = 30;
        GameTestSupport.platformAt(h, floor, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, floor + 1, 2);
        var server = h.getLevel().getServer();
        CompanionEntity companion = CompanionManager.get(server).summon(player);
        BlockPos far = h.absolutePos(new BlockPos(12, floor + 1, 12));
        companion.teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
        FriendaiServerState.get(server).sessions().runTool(player.getUUID(), "stay", "{}");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(t -> t.describe().equals("staying in place"))
                        .orElse(false),
                "precondition: companion is staying and far away");
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.TELEPORT));
        h.assertTrue(
                companion.position().distanceTo(player.position()) <= 4.0,
                "companion stayed at " + companion.position());
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(t -> !t.type().equals("stay"))
                        .orElse(true),
                "teleport must stop the previous task");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void summonExistingCompanionDoesNotTeleport(GameTestHelper h) {
        int floor = 20;
        GameTestSupport.platformAt(h, floor, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, floor + 1, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 12, floor + 1, 12);
        companion.setOwner(player.getUUID());
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.register(companion);
        Vec3 before = companion.position();
        h.assertTrue(manager.summon(player) == companion, "summon should keep the existing companion");
        h.assertTrue(
                companion.position().distanceTo(before) < 0.01,
                "summon moved the companion from " + before + " to " + companion.position());
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(t -> t.type().equals("follow"))
                        .orElse(false),
                "summon should start the follow task");
        h.succeed();
    }

    /** Answers from a queue; an exhausted script fails the request. */
    static final class Script implements LlmGateway {
        final Queue<LlmResponse> responses = new ArrayDeque<>();

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            LlmResponse next = responses.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("script exhausted"))
                    : CompletableFuture.completedFuture(next);
        }

        Script text(String text) {
            responses.add(new LlmResponse(text, List.of(), StopReason.END, new Usage(0, 0)));
            return this;
        }

        Script call(String name, String json) {
            JsonObject args = JsonParser.parseString(json).getAsJsonObject();
            responses.add(new LlmResponse(
                    "", List.of(new ToolCall("call_1", name, args, json, null)), StopReason.TOOL_USE, new Usage(0, 0)));
            return this;
        }
    }

    @GameTest(maxTicks = 100)
    public void manualToolRunWithMalformedJsonReturnsError(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        CompanionManager.get(server).summon(player);
        ToolResult result = brains.runTool(player.getUUID(), "stay", "{not json");
        h.assertTrue(result.error(), "malformed JSON must be an error result");
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
        h.assertTrue(log.stream().anyMatch(e -> e.kind() == LogKind.MANUAL), "manual run must be logged: " + log);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void manualToolRunExecutesTool(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        var companion = CompanionManager.get(server).summon(player);
        ToolResult result = brains.runTool(player.getUUID(), "stay", "{}");
        h.assertTrue(!result.error(), "stay should succeed: " + result.content());
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(t -> t.describe().equals("staying in place"))
                        .orElse(false),
                "companion should be staying");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void runToolWithoutCompanionIsError(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionSessions brains =
                FriendaiServerState.get(h.getLevel().getServer()).sessions();
        ToolResult result = brains.runTool(player.getUUID(), "stay", "{}");
        h.assertTrue(result.error(), "no companion must be an error result");
        h.succeed();
    }

    @GameTest(maxTicks = 900)
    public void taskHistoryRecordsFinishedTask(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(4, 2, 2), Blocks.CHERRY_LOG);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(new BlockPos(4, 2, 2))),
                "cherry log should be directly visible");
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        Script script = new Script()
                .call("mine_blocks", "{\"block\":\"minecraft:cherry_log\",\"count\":1}")
                .text("On it")
                .text("Done");
        brains.setGatewayOverride(player.getUUID(), script);
        brains.post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(player.getUUID(), "Steve", "mine a log", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            List<TaskRecord> recent = brains.debug().historyOf(player.getUUID()).recent();
            h.assertTrue(
                    recent.stream()
                            .anyMatch(r -> r.toolName().equals("mine_blocks")
                                    && r.status().equals("SUCCESS")),
                    "history so far: " + recent);
        });
    }

    @GameTest(maxTicks = 100)
    public void logCapturesBrainTurn(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        brains.setGatewayOverride(player.getUUID(), new Script().text("Hi"));
        CompanionManager.get(server).summon(player);
        brains.post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(player.getUUID(), "Steve", "hello", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
            h.assertTrue(log.stream().anyMatch(e -> e.kind() == LogKind.USER), "no USER entry: " + log);
            h.assertTrue(log.stream().anyMatch(e -> e.kind() == LogKind.ASSISTANT), "no ASSISTANT entry: " + log);
        });
    }

    @GameTest(maxTicks = 100)
    public void logNeverContainsConfiguredApiKey(GameTestHelper h) {
        String key = "sk-test-12345678";
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        ServerConfig original = state.config();
        ServerConfig withKey = ServerConfig.defaults();
        withKey.profiles.get(withKey.activeProfile).apiKey = key;
        state.applyConfig(withKey);
        CompanionSessions brains = state.sessions();
        brains.setGatewayOverride(player.getUUID(), new Script().text("my key is " + key));
        CompanionManager.get(server).summon(player);
        brains.post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(player.getUUID(), "Steve", "what is your key", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
            h.assertTrue(log.stream().anyMatch(e -> e.kind() == LogKind.ASSISTANT), "no ASSISTANT entry yet");
            h.assertTrue(log.stream().noneMatch(e -> e.text().contains(key)), "key leaked into the log: " + log);
            state.applyConfig(original);
        });
    }

    static {
        // Tests fire several requests per player back to back.
        CompanionRequestHandler.setLimiter(new RateLimiter(0, System::currentTimeMillis));
    }

    private static void op(ServerPlayer player) {
        // The GameTest server grants every op level ALL, so ask for the gamemaster level explicitly.
        player.level()
                .getServer()
                .getPlayerList()
                .op(
                        player.nameAndId(),
                        java.util.Optional.of(LevelBasedPermissionSet.GAMEMASTER),
                        java.util.Optional.empty());
    }

    private static void deop(ServerPlayer player) {
        player.level().getServer().getPlayerList().deop(player.nameAndId());
    }

    @GameTest(maxTicks = 100)
    public void summonRequestCreatesCompanionAndDismissRemovesIt(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var companions = CompanionManager.get(h.getLevel().getServer());
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SUMMON));
        h.assertTrue(companions.find(player.getUUID()).isPresent(), "summon request should create a companion");
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.DISMISS));
        h.assertTrue(companions.find(player.getUUID()).isEmpty(), "dismiss request should remove the companion");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void stopRequestCancelsCurrentTask(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        var companion = CompanionManager.get(server).summon(player);
        brains.runTool(player.getUUID(), "stay", "{}");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(t -> t.describe().equals("staying in place"))
                        .orElse(false),
                "precondition: companion is staying");
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.STOP));
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(t -> !t.describe().equals("staying in place"))
                        .orElse(true),
                "stop request should end the stay task");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void nonOperatorCannotRunToolOrReadLog(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        h.assertTrue(!Operators.isOperator(player), "precondition: mock player is not an operator");
        CompanionManager.get(server).summon(player);
        brains.debug().logOf(player.getUUID()).add(LogKind.ERROR, "secret detail");
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.RUN_TOOL, "stay", "{}"));
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SEND_MESSAGE, "hello", ""));
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.REQUEST_PROMPT));
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
        h.assertTrue(
                log.stream().noneMatch(e -> e.kind() == LogKind.MANUAL || e.kind() == LogKind.USER),
                "denied requests must not reach the brain: " + log);
        CompanionSnapshot snapshot = SnapshotBuilder.build(player, 0);
        h.assertTrue(!snapshot.operator(), "snapshot must say the player is not an operator");
        h.assertTrue(snapshot.newLog().isEmpty(), "no log for non-operators");
        h.assertTrue(snapshot.perception().isEmpty(), "no perception for non-operators");
        h.assertTrue(snapshot.toolNames().isEmpty(), "no tool names for non-operators");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void operatorSnapshotHasLogPerceptionAndTools(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        op(player);
        try {
            CompanionManager.get(server).summon(player);
            brains.debug().logOf(player.getUUID()).add(LogKind.USER, "hello");
            CompanionSnapshot snapshot = SnapshotBuilder.build(player, 0);
            h.assertTrue(snapshot.present() && snapshot.operator(), "present operator snapshot expected");
            h.assertTrue(!snapshot.newLog().isEmpty(), "operators get the log");
            h.assertTrue(!snapshot.perception().isBlank(), "operators get perception");
            h.assertTrue(snapshot.toolNames().contains("stay"), "tool names: " + snapshot.toolNames());
            h.assertTrue(
                    SnapshotCodec.decode(SnapshotCodec.encode(snapshot)).equals(snapshot),
                    "snapshot must survive the codec");
        } finally {
            deop(player);
        }
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void operatorSendMessageReachesBrain(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        brains.setGatewayOverride(player.getUUID(), new Script().text("Hi"));
        op(player);
        try {
            CompanionManager.get(server).summon(player);
            CompanionRequestHandler.handle(
                    player, new CompanionRequestC2SPayload(RequestType.SEND_MESSAGE, "hello", ""));
        } finally {
            deop(player);
        }
        h.succeedWhen(() -> h.assertTrue(
                brains.debug().logOf(player.getUUID()).after(0, 50).stream().anyMatch(e -> e.kind() == LogKind.USER),
                "message should reach the brain"));
    }

    @GameTest(maxTicks = 100)
    public void snapshotWithoutCompanionIsNotPresent(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionSnapshot snapshot = SnapshotBuilder.build(player, 0);
        h.assertTrue(!snapshot.present(), "no companion, not present");
        h.assertTrue(snapshot.currentTask().isEmpty() && snapshot.perception().isEmpty(), "no task or perception");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void logFloodIsBoundedPerSnapshot(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        op(player);
        try {
            CompanionManager.get(server).summon(player);
            for (int i = 0; i < 500; i++) {
                brains.debug().logOf(player.getUUID()).add(LogKind.TOOL_RESULT, "x".repeat(5000));
            }
            CompanionSnapshot snapshot = SnapshotBuilder.build(player, 0);
            h.assertTrue(
                    snapshot.newLog().size() == SnapshotBuilder.MAX_LOG_ENTRIES,
                    "entries: " + snapshot.newLog().size());
            h.assertTrue(SnapshotCodec.encode(snapshot).length < (1 << 20), "snapshot must fit the packet limit");
        } finally {
            deop(player);
        }
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void subscriberIsDroppedAfterDisconnect(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionSessions brains =
                FriendaiServerState.get(h.getLevel().getServer()).sessions();
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SUBSCRIBE));
        h.assertTrue(brains.debug().subscribers().contains(player.getUUID()), "subscribed");
        h.assertTrue(companion.menuOpen() && companion.executionCoordinator().held(), "held while the menu is open");
        DebugStream.onDisconnect(player);
        h.assertTrue(!brains.debug().subscribers().contains(player.getUUID()), "unsubscribed after disconnect");
        h.assertTrue(!companion.executionCoordinator().held(), "a disconnect must not leave the companion held");
        h.succeed();
    }

    @GameTest(maxTicks = 120)
    public void openMenuHoldsTheRunnerAndClosingResumesTheTask(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        CountingTask task = new CountingTask();
        companion.executionCoordinator().start(task, "test_counting");
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SUBSCRIBE));
        h.assertTrue(companion.executionCoordinator().held(), "held while the menu is open");
        int before = task.ticks;
        h.runAfterDelay(20, () -> {
            h.assertValueEqual(task.ticks, before, "a held companion must not tick its task");
            CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.UNSUBSCRIBE));
            h.assertTrue(!companion.executionCoordinator().held(), "released when the menu closes");
        });
        h.runAfterDelay(40, () -> {
            h.assertTrue(task.ticks > before, "the task resumes after the menu closes");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void aDuplicateSubscribeHoldsOnlyOnce(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SUBSCRIBE));
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SUBSCRIBE));
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.UNSUBSCRIBE));
        h.assertTrue(!companion.executionCoordinator().held(), "one close releases a duplicated subscribe");
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aCompanionSummonedWithTheMenuOpenStartsHeld(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SUBSCRIBE));
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        h.assertTrue(
                companion.menuOpen() && companion.executionCoordinator().held(), "starts paused behind the open menu");
        CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.UNSUBSCRIBE));
        h.assertTrue(!companion.executionCoordinator().held(), "resumes when the menu closes");
        h.succeed();
    }

    /** A task that only counts its ticks, so a test can tell whether the runner advanced it. */
    private static final class CountingTask implements Task {
        int ticks;

        @Override
        public String type() {
            return "test_counting";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            ticks++;
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "counting";
        }

        @Override
        public String resultSummary() {
            return "counted";
        }

        @Override
        public boolean emitsEvents() {
            return false;
        }

        @Override
        public boolean hasTimeout() {
            return false;
        }
    }

    @GameTest(maxTicks = 100)
    public void malformedRequestsAreIgnored(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionManager.get(server).summon(player);
        op(player);
        try {
            CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.INVALID));
            CompanionRequestHandler.handle(
                    player, new CompanionRequestC2SPayload(RequestType.RUN_TOOL, "x".repeat(10_000), "{}"));
            CompanionRequestHandler.handle(
                    player, new CompanionRequestC2SPayload(RequestType.RUN_TOOL, "stay", "[1,2"));
            CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.SEND_MESSAGE, "   ", ""));
            CompanionRequestHandler.handle(player, new CompanionRequestC2SPayload(RequestType.RUN_TOOL, "nope", "{}"));
        } finally {
            deop(player);
        }
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void promptContainsCompanionName(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        CompanionManager.get(server).summon(player);
        var prompt = state.sessions().currentPrompt(player.getUUID());
        h.assertTrue(prompt.isPresent(), "prompt should exist for a companion");
        h.assertTrue(prompt.get().contains(state.config().companion.name), "prompt: " + prompt.get());
        h.assertTrue(state.sessions().currentPrompt(java.util.UUID.randomUUID()).isEmpty(), "no companion, no prompt");
        h.succeed();
    }
}
