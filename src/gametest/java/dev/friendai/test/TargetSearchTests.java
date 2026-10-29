package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.task.ResourceSearchDriver;
import dev.friendai.features.acquisition.minecraft.task.SearchResourceTask;
import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.SearchSession;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Search over target kinds, terrain scoring, the owner escort, and a clean budget failure. */
public final class TargetSearchTests {
    @GameTest(maxTicks = 400)
    public void choosesLandEastOverTheSea(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 56);
        GameTestSupport.keepLoaded(h, -40, -4, 64, 64);
        // Ocean west of the platform: a 3x3 sample on those frontiers is water.
        for (int x = -32; x < 0; x++) {
            for (int z = 0; z < 56; z++) {
                h.setBlock(x, 299, z, Blocks.STONE);
                h.setBlock(x, 300, z, Blocks.WATER);
                h.setBlock(x, 301, z, Blocks.WATER);
            }
        }
        // A forest edge east, hidden behind a wall from the starting viewpoint.
        for (int z = 0; z <= 14; z++) {
            for (int y = 301; y <= 307; y++) h.setBlock(28, y, z, Blocks.STONE);
        }
        for (int x = 36; x <= 39; x++) {
            for (int z = 0; z <= 4; z++) {
                for (int y = 301; y <= 304; y++) h.setBlock(x, y, z, Blocks.OAK_LOG);
            }
        }
        var owner = GameTestSupport.mockPlayerAt(h, 4, 301, 4);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 4, 301, 4);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        companion.executionCoordinator().setListener(result);
        JsonObject args = new JsonObject();
        args.addProperty("resource", "#minecraft:logs");
        args.addProperty("strategy", "surface");
        args.addProperty("scope", "local");
        args.addProperty("heading", "east");
        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new ToolCall("test", "search_resource", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        int startX = companion.blockPosition().getX();
        h.succeedWhen(() -> {
            String choice = result.debug.stream()
                    .filter(line -> line.startsWith("search chose"))
                    .findFirst()
                    .orElse(null);
            h.assertTrue(choice != null, "search should choose a frontier; debug: " + result.debug);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("search chose (-?\\d+) (-?\\d+)")
                    .matcher(choice);
            h.assertTrue(matcher.find(), "unreadable choice: " + choice);
            int chosenX = Integer.parseInt(matcher.group(1));
            h.assertTrue(chosenX > startX, "search chose the sea side: " + choice);
            companion.executionCoordinator().stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 400)
    public void waitsForALaggingOwnerAndResumes(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        companion.executionCoordinator().setListener(result);
        JsonObject args = new JsonObject();
        args.addProperty("resource", "#minecraft:logs");
        args.addProperty("strategy", "surface");
        args.addProperty("scope", "local");
        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new ToolCall("test", "search_resource", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        int[] step = {0};
        BlockPos[] held = {null};
        h.succeedWhen(() -> {
            step[0]++;
            if (step[0] == 8) {
                Vec3 far = h.absoluteVec(new Vec3(80, 2, 80));
                owner.teleportTo(far.x, far.y, far.z);
            } else if (step[0] == 24) {
                String summary = companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.resultSummary())
                        .orElse("");
                h.assertTrue(summary.contains("waiting for the owner"), "expected a wait, got: " + summary);
                held[0] = companion.blockPosition();
            } else if (step[0] > 24 && step[0] < 30) {
                h.assertTrue(
                        companion.blockPosition().equals(held[0]), "the companion moved while waiting for the owner");
            } else if (step[0] == 30) {
                Vec3 near = h.absoluteVec(new Vec3(4, 2, 4));
                owner.teleportTo(near.x, near.y, near.z);
            } else if (step[0] >= 40) {
                String summary = companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.resultSummary())
                        .orElse("");
                h.assertTrue(!summary.contains("waiting for the owner"), "did not resume: " + summary);
                companion.executionCoordinator().stop("test cleanup");
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void failsCleanlyInsideItsBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        String dimension = h.getLevel().dimension().identifier().toString();
        long now = h.getLevel().getServer().overworld().getGameTime();
        var session = new SearchSession(
                UUID.randomUUID(),
                dimension,
                "biome:minecraft:the_void",
                new Pos(2, 2, 2),
                new SearchSession.Limits(32, 128, 160, 32),
                now);
        var target = new TargetKind.Biome("minecraft:the_void", 32);
        var profile = new ResourceSearchProfile(
                "biome:minecraft:the_void",
                Set.of(dimension),
                OptionalInt.empty(),
                Set.of(ResourceSearchProfile.Strategy.SURFACE));
        var driver = new ResourceSearchDriver(
                session, target, profile, 32, false, ResourceSearchProfile.Strategy.SURFACE, null, null, null);
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 600, () -> false);
        runner.setListener(result);
        runner.start(driver, "search");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "search should stop inside its budget");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("no directly visible") || result.summary.contains("budget"),
                    "failure must name the reason: " + result.summary);
        });
    }

    @GameTest(maxTicks = 80)
    public void resourceSearchHasNoResumableTaskCheckpoint(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        JsonObject args = new JsonObject();
        args.addProperty("resource", "#minecraft:logs");
        args.addProperty("strategy", "surface");
        args.addProperty("scope", "local");
        GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new ToolCall("test", "search_resource", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        var task = companion.executionCoordinator().current().orElseThrow();
        h.assertTrue(task.type().equals(SearchResourceTask.TYPE), "active task: " + task.type());
        h.assertTrue(
                dev.friendai.server.execution.persistence.TaskRestoreRegistry.restoreCheckpoint(
                                new dev.friendai.task.TaskState(SearchResourceTask.TYPE, ""), h.getLevel())
                        .isEmpty(),
                "resource searches require a fresh request after restart");
        h.assertTrue(
                dev.friendai.server.execution.persistence.TaskRestoreRegistry.restoreCheckpoint(
                                new dev.friendai.task.TaskState("unknown_task", ""), h.getLevel())
                        .isEmpty(),
                "unknown task types must be rejected");
        companion.executionCoordinator().stop("test cleanup");
        h.succeed();
    }

    @GameTest(maxTicks = 120)
    public void findsAVillageMarkerAndTheCurrentBiome(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        h.setBlock(6, 2, 2, Blocks.BELL);
        BlockPos bell = h.absolutePos(new BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, companion, bell), "the bell must be in sight");
        Result result = new Result();
        companion.executionCoordinator().setListener(result);
        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        targetCall("structure", "village"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        String biome = h.getLevel()
                .getBiome(companion.blockPosition())
                .unwrapKey()
                .map(key -> key.identifier().toString())
                .orElseThrow();
        Result biomeResult = new Result();
        boolean[] biomeStarted = {false};
        h.succeedWhen(() -> {
            if (!biomeStarted[0]) {
                h.assertTrue(result.status != null, "structure search should finish");
                h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
                h.assertTrue(result.summary.contains("found"), result.summary);
                biomeStarted[0] = true;
                companion.executionCoordinator().setListener(biomeResult);
                var biomeStart = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                        .bind(companion)
                        .execute(
                                targetCall("biome", biome),
                                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
                h.assertTrue(!biomeStart.error(), biomeStart.content());
                return;
            }
            h.assertTrue(
                    biomeResult.status == TaskStatus.SUCCESS,
                    "biome status " + biomeResult.status + " " + biomeResult.summary);
        });
    }

    @GameTest(maxTicks = 120)
    public void findsARememberedPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        BlockPos at = companion.blockPosition();
        FriendaiServerState.get(h.getLevel().getServer())
                .placeService()
                .remember(
                        companion,
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER),
                        new PlaceValues(
                                "camp",
                                PlaceKind.HOUSE,
                                "",
                                h.getLevel().dimension().identifier().toString(),
                                new Pos(at.getX(), at.getY(), at.getZ()),
                                8,
                                Set.of(),
                                PlaceSource.MANUAL,
                                owner.getUUID(),
                                ""),
                        h.getLevel().getServer().overworld().getGameTime());
        Result result = new Result();
        companion.executionCoordinator().setListener(result);
        var started = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        targetCall("place", "camp"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            h.assertTrue(result.status != null, "place search should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("found"), result.summary);
        });
    }

    private static ToolCall targetCall(String kind, String target) {
        JsonObject args = new JsonObject();
        args.addProperty("kind", kind);
        args.addProperty("target", target);
        args.addProperty("scope", "local");
        return new ToolCall("test", "find_target", args, args.toString(), null);
    }

    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        final java.util.List<String> debug = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onDebug(String text) {
            if (text != null) debug.add(text);
        }

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}
    }
}
