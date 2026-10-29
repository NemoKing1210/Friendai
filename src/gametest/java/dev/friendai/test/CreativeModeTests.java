package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.core.CreativeModeService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.RequestType;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.creative.minecraft.task.CreateItemsTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.net.CompanionRequestC2SPayload;
import dev.friendai.net.CompanionRequestHandler;
import dev.friendai.net.Operators;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public class CreativeModeTests {
    @GameTest
    public void creativePreferenceDefaultsOffAndSurvivesDismissal(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionLifeData life =
                FriendaiServerState.get(h.getLevel().getServer()).companionLife();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());

        h.assertFalse(life.creativeMode(owner.getUUID()), "new owner should default to survival");
        CompanionEntity first = manager.summon(owner);
        h.assertFalse(first.creativeMode(), "new companion should start in survival");

        life.setCreativeMode(owner.getUUID(), true);
        h.assertTrue(manager.dismiss(owner), "companion should dismiss");
        CompanionEntity resummoned = manager.summon(owner);
        h.assertTrue(resummoned.creativeMode(), "saved owner preference should apply to a resummoned companion");
        h.assertTrue(life.creativeMode(owner.getUUID()), "dismissal should preserve the creative preference");
        CompoundTag saved = (CompoundTag) CompanionLifeData.TYPE
                .codec()
                .encodeStart(NbtOps.INSTANCE, life)
                .getOrThrow();
        CompanionLifeData loaded =
                CompanionLifeData.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow();
        h.assertTrue(loaded.creativeMode(owner.getUUID()), "saved creative preference should survive a world reload");
        h.succeed();
    }

    @GameTest
    public void creativePreferenceSurvivesDimensionReplacement(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        FriendaiServerState.get(h.getLevel().getServer()).companionLife().setCreativeMode(owner.getUUID(), true);
        companion.syncCreativeModePreference();
        var destination = h.getLevel().getServer().getLevel(Level.NETHER);
        var moved = companion.teleport(new TeleportTransition(
                destination, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));

        h.assertTrue(moved instanceof CompanionEntity, "companion should transfer to another dimension");
        h.assertTrue(
                ((CompanionEntity) moved).creativeMode(),
                "replacement entity should load the owner's creative preference");
        h.succeed();
    }

    @GameTest
    public void activationRequiresOperatorOwnerButOwnerCanDisableAfterRevocation(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertFalse(Operators.isOperator(owner), "test owner unexpectedly has operator permission");
        h.assertTrue(
                CreativeModeService.requestMode(companion, guest, true) == CreativeModeService.Result.DENIED,
                "a guest must not activate creative");
        h.assertTrue(
                CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.DENIED,
                "a non-operator owner must not activate creative");

        companion.executionCoordinator().setPaused(true);
        int[] callbacks = {0};
        companion.executionCoordinator().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {
                callbacks[0]++;
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {
                callbacks[0]++;
            }

            @Override
            public void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {
                callbacks[0]++;
            }
        });
        companion
                .executionCoordinator()
                .enqueue(
                        new QueuedStep(
                                "stay",
                                new com.google.gson.JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.STOP_PLAN,
                                1,
                                List.of()),
                        ctx -> new StayTask());
        h.assertTrue(companion.executionCoordinator().queue().jobCount() == 1, "test job was not queued");
        FriendaiServerState.get(h.getLevel().getServer()).companionLife().setCreativeMode(owner.getUUID(), true);
        companion.syncCreativeModePreference();

        h.assertTrue(
                CreativeModeService.requestMode(companion, owner, false) == CreativeModeService.Result.CHANGED,
                "the owner must be able to disable creative after activation rights are revoked");
        h.assertFalse(companion.creativeMode(), "creative mode was not disabled");
        h.assertFalse(
                FriendaiServerState.get(h.getLevel().getServer())
                        .companionLife()
                        .creativeMode(owner.getUUID()),
                "the saved preference was not disabled");
        h.assertTrue(companion.executionCoordinator().queue().jobCount() == 0, "mode change must cancel queued work");
        h.assertTrue(callbacks[0] == 0, "mode change must not emit a task failure or replan event");
        h.succeed();
    }

    @GameTest
    public void creativeModeRejectsQueuedWorldActionFromGuest(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.executionCoordinator().setPaused(true);
        companion
                .executionCoordinator()
                .enqueue(
                        new QueuedStep(
                                "stay",
                                new com.google.gson.JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.STOP_PLAN,
                                1,
                                List.of(),
                                guest.getUUID()),
                        ctx -> new StayTask());
        FriendaiServerState.get(h.getLevel().getServer()).companionLife().setCreativeMode(owner.getUUID(), true);
        companion.syncCreativeModePreference();
        companion.executionCoordinator().setPaused(false);

        companion.executionCoordinator().tick();

        h.assertTrue(
                companion.executionCoordinator().queue().jobCount() == 0,
                "a guest's queued world-changing task must be discarded while creative is active");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> !task.type().equals("stay"))
                        .orElse(true),
                "a guest's creative task must never start");
        h.succeed();
    }

    @GameTest
    public void queuedPlannerCreativeActionCannotRunAfterModeExit(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.level()
                .getServer()
                .getPlayerList()
                .op(owner.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(
                CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");
        companion.executionCoordinator().setPaused(true);

        JsonObject args = new JsonObject();
        args.addProperty("item", "minecraft:diamond");
        args.addProperty("count", 128);
        QueuedStep proposedByPlanner = new QueuedStep(
                "create_item",
                args,
                null,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.STOP_PLAN,
                1,
                List.of(),
                owner.getUUID(),
                true);
        companion
                .executionCoordinator()
                .enqueue(
                        proposedByPlanner,
                        context -> new CreateItemsTask(
                                new ItemStack(Items.DIAMOND), 128, CreateItemsTask.Destination.SELF));
        h.assertTrue(companion.executionCoordinator().queue().jobCount() == 1, "test planner proposal was not queued");

        h.assertTrue(
                CreativeModeService.requestMode(companion, owner, false) == CreativeModeService.Result.CHANGED,
                "owner could not turn creative mode off");
        companion.executionCoordinator().setPaused(false);
        for (int i = 0; i < 4; i++) companion.executionCoordinator().tick();

        h.assertFalse(companion.creativeMode(), "creative mode stayed enabled");
        h.assertTrue(
                companion.executionCoordinator().queue().jobCount() == 0,
                "mode exit retained a planner-created creative job");
        h.assertTrue(
                companion.inventory().countItem(Items.DIAMOND) == 0,
                "planner proposal regained creative material privileges after mode exit");
        h.succeed();
    }

    @GameTest
    public void creativeMenuRequestIsExplicitOwnerScopedAndRejectsUnauthorizedEnable(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        var life = FriendaiServerState.get(h.getLevel().getServer()).companionLife();

        CompanionRequestHandler.handle(
                owner, new CompanionRequestC2SPayload(RequestType.SET_CREATIVE_MODE, "enabled", ""));
        h.assertFalse(life.creativeMode(owner.getUUID()), "invalid request values must be ignored");
        CompanionRequestHandler.handle(
                owner, new CompanionRequestC2SPayload(RequestType.SET_CREATIVE_MODE, "true", ""));
        h.assertFalse(life.creativeMode(owner.getUUID()), "a non-operator owner cannot enable creative");

        life.setCreativeMode(owner.getUUID(), true);
        companion.syncCreativeModePreference();
        companion.executionCoordinator().setPaused(true);
        companion
                .executionCoordinator()
                .enqueue(
                        new QueuedStep(
                                "check_container",
                                new com.google.gson.JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.STOP_PLAN,
                                1,
                                List.of(),
                                owner.getUUID(),
                                false),
                        ctx -> new StayTask());
        CompanionRequestHandler.handle(
                owner, new CompanionRequestC2SPayload(RequestType.SET_CREATIVE_MODE, "true", ""));
        h.assertTrue(
                companion.executionCoordinator().queue().jobCount() == 1,
                "an idempotent request must not cancel queued work");

        CompanionRequestHandler.handle(
                guest, new CompanionRequestC2SPayload(RequestType.SET_CREATIVE_MODE, "false", ""));
        h.assertTrue(
                companion.creativeMode(), "a forged request from another player cannot change the owner's companion");
        CompanionRequestHandler.handle(
                owner, new CompanionRequestC2SPayload(RequestType.SET_CREATIVE_MODE, "false", ""));
        h.assertFalse(companion.creativeMode(), "the owner must be able to disable creative");
        h.succeed();
    }
}
