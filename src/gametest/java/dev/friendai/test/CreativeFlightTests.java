package dev.friendai.test;

import dev.friendai.core.CreativeModeService;
import dev.friendai.creative.CreativeAction;
import dev.friendai.creative.CreativeActionContext;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.FlyToTask;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CreativeFlightTests {
    private static CompanionEntity creativeCompanion(GameTestHelper h, ServerPlayer owner) {
        owner.level().getServer().getPlayerList().op(owner.nameAndId(),
                Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");
        return companion;
    }

    private static Caller caller(ServerPlayer player) {
        return new Caller(player.getUUID(), player.getName().getString(), AccessLevel.OWNER);
    }

    @GameTest(maxTicks = 500)
    public void flyToRoutesAroundWallAndAscends(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 14, 2, 14);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 2, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        for (int y = 2; y <= 5; y++) {
            for (int z = 0; z < 6; z++) h.setBlock(6, y, z, net.minecraft.world.level.block.Blocks.STONE);
        }
        Vec3 target = h.absoluteVec(new Vec3(10.5, 8, 2.5));
        h.assertTrue(CreativeModeService.isActionAuthorized(companion,
                new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()), CreativeAction.FLIGHT),
                "flight authorization was not available to the operator owner");
        h.assertTrue(h.getLevel().hasChunkAt(BlockPos.containing(target)), "flight test target chunk is not loaded");
        AABB targetBox = companion.getBoundingBox().move(target.subtract(companion.position()));
        h.assertTrue(h.getLevel().noCollision(companion, targetBox), "flight test target has a collision");
        FlyToTask task = new FlyToTask(target);
        companion.taskRunner().start(task, "fly_to", caller(owner), true);
        AABB wall = new AABB(h.absoluteVec(new Vec3(6, 2, 0)), h.absoluteVec(new Vec3(7, 6, 6)));
        h.succeedWhen(() -> {
            h.assertFalse(companion.getBoundingBox().intersects(wall), "companion clipped through the wall");
            h.assertTrue(companion.position().distanceTo(target) <= FlightTolerance.VALUE,
                    "companion has not reached target; current position " + companion.position()
                            + "; result=" + task.resultSummary() + "; route=" + task.navigationDebug()
                            + "; noGravity=" + companion.isNoGravity());
            h.assertTrue(task.resultSummary().startsWith("flew to "), "unexpected result: " + task.resultSummary());
        });
    }

    @GameTest(maxTicks = 500)
    public void followUsesFlightAcrossAChasm(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 12, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 2, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        for (int x = 4; x <= 9; x++) {
            for (int z = 0; z < 6; z++) h.setBlock(x, 1, z, net.minecraft.world.level.block.Blocks.AIR);
        }
        companion.taskRunner().start(new FollowTask(false), "follow_player", caller(owner), true);
        h.succeedWhen(() -> h.assertTrue(companion.position().distanceTo(owner.position()) <= 5.0,
                "follow did not cross the gap; companion=" + companion.position() + ", owner=" + owner.position()));
    }

    @GameTest(maxTicks = 400)
    public void followKeepsCreativeCompanionHoveringAfterArrival(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 12, 10, 2);
        owner.setNoGravity(true);
        owner.setDeltaMovement(Vec3.ZERO);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 10, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        companion.taskRunner().start(new FollowTask(false), "follow_player", caller(owner), true);

        final int[] hoveringTicks = {0};
        final double[] hoverY = {Double.NaN};
        h.succeedWhen(() -> {
            double distance = companion.position().distanceTo(owner.position());
            if (distance <= companion.body().followDistance() + 2.0) {
                if (Double.isNaN(hoverY[0])) hoverY[0] = companion.getY();
                hoveringTicks[0]++;
                h.assertTrue(companion.isNoGravity(), "follow stopped flight gravity when it arrived");
                h.assertTrue(Math.abs(companion.getY() - hoverY[0]) < 0.05,
                        "companion drifted vertically while hovering: start=" + hoverY[0]
                                + ", now=" + companion.getY());
            }
            h.assertTrue(hoveringTicks[0] >= 30,
                    "companion did not remain at the follow position for 30 ticks; position="
                            + companion.position() + ", distance=" + distance);
        });
    }

    @GameTest(maxTicks = 300)
    public void disablingCreativeStartsAndCompletesBoundedLanding(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos high = h.absolutePos(new BlockPos(3, 10, 3));
        companion.snapTo(high.getX() + 0.5, high.getY(), high.getZ() + 0.5, 0, 0);
        companion.beginCreativeFlight();
        h.assertTrue(companion.isNoGravity(), "flight did not enable no-gravity movement");
        h.assertTrue(CreativeModeService.requestMode(companion, owner, false) == CreativeModeService.Result.CHANGED,
                "operator owner could not disable creative mode");
        h.assertTrue(companion.creativeLanding(), "mode exit did not enter landing");
        h.succeedWhen(() -> {
            h.assertTrue(!companion.creativeLanding(), "landing did not finish before its timeout");
            h.assertFalse(companion.isNoGravity(), "gravity remained disabled after landing");
            h.assertTrue(companion.onGround(), "companion did not land on the platform");
        });
    }

    @GameTest(maxTicks = 300)
    public void flightFailsCleanlyAtUnloadedChunks(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 2, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        // Keep it beyond the nearby structures used by other tests in this class, whose forced chunks can reach +64.
        BlockPos target = BlockPos.containing(companion.position().add(256, 0, 0));
        h.assertFalse(h.getLevel().hasChunkAt(target), "test target chunk should be unloaded before flight starts");
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.taskRunner().setListener(new TaskListener() {
            @Override public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }
            @Override public void onCancelled(int id, String toolName, String reason) {}
        });
        Vec3 targetPosition = new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        companion.taskRunner().start(new FlyToTask(targetPosition), "fly_to", caller(owner), true);
        h.succeedWhen(() -> {
            h.assertTrue(status[0] == TaskStatus.FAILED, "unloaded route status was " + status[0]);
            h.assertTrue(summary[0].contains("loaded collision-safe"), "unexpected failure: " + summary[0]);
            h.assertFalse(companion.isNoGravity(), "failed route left no-gravity enabled");
        });
    }

    @GameTest(maxTicks = 400)
    public void creativeFollowDropsHoverWhenOwnerLosesOperatorRights(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 12, 10, 2);
        owner.setNoGravity(true);
        owner.setDeltaMovement(Vec3.ZERO);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 10, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        companion.taskRunner().start(new FollowTask(false), "follow_player", caller(owner), true);

        final int[] hoveringTicks = {0};
        final int[] revokedAtTick = {-1};
        h.succeedWhen(() -> {
            if (revokedAtTick[0] < 0) {
                double distance = companion.position().distanceTo(owner.position());
                if (distance <= companion.body().followDistance() + 2.0) {
                    hoveringTicks[0]++;
                    h.assertTrue(companion.isNoGravity(), "follow stopped flight gravity before permission was revoked");
                }
                if (hoveringTicks[0] >= 30) {
                    owner.level().getServer().getPlayerList().deop(owner.nameAndId());
                    revokedAtTick[0] = companion.tickCount;
                }
                h.assertTrue(hoveringTicks[0] >= 30,
                        "companion did not hover at the follow position for 30 ticks; position=" + companion.position());
            }
            h.assertTrue(revokedAtTick[0] >= 0, "owner operator rights were not revoked");
            h.assertTrue(companion.tickCount > revokedAtTick[0], "permission revocation was not followed by a tick");
            h.assertFalse(companion.isNoGravity(), "companion kept hovering after its owner's flight right was revoked");
        });
    }
    private static final class FlightTolerance {
        private static final double VALUE = 1.0;
    }
}
