package dev.friendai.test;

import dev.friendai.core.CreativeModeService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.features.navigation.minecraft.nav.FlightNavigator;
import dev.friendai.features.navigation.minecraft.task.FlyToTask;
import dev.friendai.features.navigation.minecraft.task.FollowTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.features.navigation.movement.Facing;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
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
        owner.level()
                .getServer()
                .getPlayerList()
                .op(owner.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(
                CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.CHANGED,
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
        h.assertTrue(
                CreativeModeService.isActionAuthorized(
                        companion,
                        new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()),
                        CreativeAction.FLIGHT),
                "flight authorization was not available to the operator owner");
        h.assertTrue(h.getLevel().hasChunkAt(BlockPos.containing(target)), "flight test target chunk is not loaded");
        AABB targetBox = companion.getBoundingBox().move(target.subtract(companion.position()));
        h.assertTrue(h.getLevel().noCollision(companion, targetBox), "flight test target has a collision");
        FlyToTask task = new FlyToTask(target);
        companion.executionCoordinator().start(task, "fly_to", caller(owner), true);
        AABB wall = new AABB(h.absoluteVec(new Vec3(6, 2, 0)), h.absoluteVec(new Vec3(7, 6, 6)));
        h.succeedWhen(() -> {
            h.assertFalse(companion.getBoundingBox().intersects(wall), "companion clipped through the wall");
            h.assertTrue(
                    companion.position().distanceTo(target) <= FlightTolerance.VALUE,
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
        companion.executionCoordinator().start(new FollowTask(false), "follow_player", caller(owner), true);
        h.succeedWhen(() -> h.assertTrue(
                companion.position().distanceTo(owner.position()) <= 5.0,
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
        companion.executionCoordinator().start(new FollowTask(false), "follow_player", caller(owner), true);

        final int[] hoveringTicks = {0};
        final double[] hoverY = {Double.NaN};
        h.succeedWhen(() -> {
            double distance = companion.position().distanceTo(owner.position());
            if (distance <= companion.body().followDistance() + 2.0) {
                if (Double.isNaN(hoverY[0])) hoverY[0] = companion.getY();
                hoveringTicks[0]++;
                h.assertTrue(companion.isNoGravity(), "follow stopped flight gravity when it arrived");
                h.assertTrue(
                        Math.abs(companion.getY() - hoverY[0]) < 0.05,
                        "companion drifted vertically while hovering: start=" + hoverY[0] + ", now="
                                + companion.getY());
            }
            h.assertTrue(
                    hoveringTicks[0] >= 30,
                    "companion did not remain at the follow position for 30 ticks; position=" + companion.position()
                            + ", distance=" + distance);
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
        h.assertTrue(
                CreativeModeService.requestMode(companion, owner, false) == CreativeModeService.Result.CHANGED,
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
        companion.executionCoordinator().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {}
        });
        Vec3 targetPosition = new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        companion.executionCoordinator().start(new FlyToTask(targetPosition), "fly_to", caller(owner), true);
        h.succeedWhen(() -> {
            h.assertTrue(status[0] == TaskStatus.FAILED, "unloaded route status was " + status[0]);
            h.assertTrue(summary[0].contains("loaded collision-safe"), "unexpected failure: " + summary[0]);
            h.assertFalse(companion.isNoGravity(), "failed route left no-gravity enabled");
        });
    }

    @GameTest(maxTicks = 400)
    public void creativeHoverEndsAfterTaskReplacementAndOperatorRevocation(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 12, 10, 2);
        owner.setNoGravity(true);
        owner.setDeltaMovement(Vec3.ZERO);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 10, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        companion.executionCoordinator().start(new FollowTask(false), "follow_player", caller(owner), true);

        final int[] hoveringTicks = {0};
        final int[] stayStartedAtTick = {-1};
        final int[] revokedAtTick = {-1};
        h.succeedWhen(() -> {
            if (hoveringTicks[0] < 30) {
                double distance = companion.position().distanceTo(owner.position());
                if (distance <= companion.body().followDistance() + 2.0) {
                    hoveringTicks[0]++;
                    h.assertTrue(
                            companion.isNoGravity(), "follow stopped flight gravity before permission was revoked");
                }
                if (hoveringTicks[0] >= 30) {
                    companion.executionCoordinator().start(new StayTask(), "stay", caller(owner), false);
                    stayStartedAtTick[0] = companion.tickCount;
                }
            }
            if (stayStartedAtTick[0] < 0) {
                h.assertTrue(
                        hoveringTicks[0] >= 30,
                        "companion did not hover at the follow position for 30 ticks; position="
                                + companion.position());
            } else if (revokedAtTick[0] < 0) {
                h.assertTrue(companion.tickCount > stayStartedAtTick[0], "stay did not replace the follow task");
                h.assertTrue(companion.isNoGravity(), "authorized stay did not preserve creative hover");
                owner.level().getServer().getPlayerList().deop(owner.nameAndId());
                revokedAtTick[0] = companion.tickCount;
            }
            h.assertTrue(revokedAtTick[0] >= 0, "owner operator rights were not revoked");
            h.assertTrue(companion.tickCount > revokedAtTick[0], "permission revocation was not followed by a tick");
            h.assertFalse(
                    companion.isNoGravity(), "companion kept hovering after its owner's flight right was revoked");
        });
    }

    private static final class FlightTolerance {
        private static final double VALUE = 1.0;
    }

    @GameTest(maxTicks = 500)
    public void flightFacesTheRoute(GameTestHelper h) {
        GameTestSupport.platform(h, 20);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 18, 2, 18);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 2, 10));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        companion.setYRot(0.0F);
        companion.setYBodyRot(0.0F);
        companion.setYHeadRot(0.0F);
        companion.setXRot(0.0F);
        Vec3 north = h.absoluteVec(new Vec3(2.5, 2, 2.5));
        Vec3 east = h.absoluteVec(new Vec3(12.5, 2, 2.5));
        companion.executionCoordinator().start(new StayTask(5000), "stay");
        TaskContext context = new TaskContext(
                companion,
                h.getLevel(),
                companion.executionCoordinator(),
                new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()));
        FlightNavigator navigator = new FlightNavigator();
        int[] leg = {0};
        int[] moving = {0};
        int[] stable = {0};
        int[] samples = {0, 0};
        float[] maxError = {0.0F, 0.0F};
        float[] finalYaw = {0.0F, 0.0F};
        float[] lastTravel = {Float.NaN};
        float[] movingTravel = {Float.NaN};
        float[] finalTravel = {Float.NaN, Float.NaN};
        boolean[] done = {false, false};
        String[] worst = {""};
        double[] lastX = {companion.getX()};
        double[] lastZ = {companion.getZ()};
        String[] last = {"not started"};
        h.onEachTick(() -> {
            Vec3 target = leg[0] == 0 ? north : east;
            FlightNavigator.State state = navigator.tick(context, target);
            last[0] = state + " " + navigator.debugStatus() + " at " + companion.position();
            if (state == FlightNavigator.State.UNREACHABLE) {
                h.fail("flight leg " + leg[0] + " unreachable: " + navigator.diagnostic() + "; " + last[0]);
            }
            double dx = companion.getX() - lastX[0];
            double dz = companion.getZ() - lastZ[0];
            lastX[0] = companion.getX();
            lastZ[0] = companion.getZ();
            if (Math.hypot(dx, dz) > 0.02) {
                moving[0]++;
                float travel = Facing.yawToward(dx, dz);
                if (!Float.isNaN(lastTravel[0])
                        && Math.abs(net.minecraft.util.Mth.wrapDegrees(travel - lastTravel[0])) < 10.0F) {
                    stable[0]++;
                } else {
                    stable[0] = 1;
                }
                lastTravel[0] = travel;
                movingTravel[0] = travel;
                if (moving[0] > 8 && stable[0] >= 4) {
                    float error = Math.abs(net.minecraft.util.Mth.wrapDegrees(companion.getYRot() - travel));
                    if (error > maxError[leg[0]]) {
                        maxError[leg[0]] = error;
                        worst[0] = "leg " + leg[0] + " tick " + h.getTick() + " travel " + travel + " yaw "
                                + companion.getYRot() + " head " + companion.getYHeadRot() + " source "
                                + companion.facing().source() + " move " + dx + "," + dz;
                    }
                    samples[leg[0]]++;
                }
            } else {
                stable[0] = 0;
                lastTravel[0] = Float.NaN;
            }
            if (state == FlightNavigator.State.ARRIVED && !done[leg[0]]) {
                done[leg[0]] = true;
                finalYaw[leg[0]] = companion.getYRot();
                finalTravel[leg[0]] = movingTravel[0];
                if (leg[0] == 0) {
                    leg[0] = 1;
                    moving[0] = 0;
                    stable[0] = 0;
                    lastTravel[0] = Float.NaN;
                    movingTravel[0] = Float.NaN;
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(
                    done[0] && done[1],
                    "both flight legs did not finish: " + done[0] + ", " + done[1] + "; last " + last[0]);
            h.assertTrue(samples[0] > 0 && samples[1] > 0, "no steady movement to measure");
            h.assertTrue(maxError[0] <= 20.0F, "north leg faced up to " + maxError[0] + " degrees away from travel");
            h.assertTrue(
                    Math.abs(net.minecraft.util.Mth.wrapDegrees(finalYaw[0] - finalTravel[0])) <= 15.0F,
                    "north leg ended facing " + finalYaw[0] + " while travelling " + finalTravel[0]);
            h.assertTrue(
                    maxError[1] <= 20.0F,
                    "east leg faced up to " + maxError[1] + " degrees away from travel; worst " + worst[0]);
            h.assertTrue(
                    Math.abs(net.minecraft.util.Mth.wrapDegrees(finalYaw[1] - finalTravel[1])) <= 15.0F,
                    "east leg ended facing " + finalYaw[1] + " while travelling " + finalTravel[1]);
        });
    }

    @GameTest(maxTicks = 300)
    public void climbKeepsTheYawAndPitches(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 14, 2, 14);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos start = h.absolutePos(new BlockPos(2, 2, 2));
        companion.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        companion.setYRot(-90.0F);
        companion.setYBodyRot(-90.0F);
        companion.setYHeadRot(-90.0F);
        companion.setXRot(0.0F);
        Vec3 up = h.absoluteVec(new Vec3(2.5, 7, 2.5));
        companion.executionCoordinator().start(new StayTask(5000), "stay");
        TaskContext context = new TaskContext(
                companion,
                h.getLevel(),
                companion.executionCoordinator(),
                new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()));
        FlightNavigator navigator = new FlightNavigator();
        float[] maxYawError = {0.0F};
        float[] minPitch = {0.0F};
        int[] climbing = {0};
        boolean[] done = {false};
        double[] lastY = {companion.getY()};
        h.onEachTick(() -> {
            FlightNavigator.State state = navigator.tick(context, up);
            double dy = companion.getY() - lastY[0];
            lastY[0] = companion.getY();
            if (dy > 0.02) {
                climbing[0]++;
                maxYawError[0] = Math.max(
                        maxYawError[0], Math.abs(net.minecraft.util.Mth.wrapDegrees(companion.getYRot() + 90.0F)));
                minPitch[0] = Math.min(minPitch[0], companion.getXRot());
            }
            if (state == FlightNavigator.State.ARRIVED) {
                done[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(done[0], "the climb did not finish");
            h.assertTrue(climbing[0] > 3, "the climb was never measured");
            h.assertTrue(maxYawError[0] <= 5.0F, "a pure climb turned the body by " + maxYawError[0]);
            h.assertTrue(minPitch[0] <= -60.0F, "a pure climb never looked up: pitch " + minPitch[0]);
        });
    }
}
