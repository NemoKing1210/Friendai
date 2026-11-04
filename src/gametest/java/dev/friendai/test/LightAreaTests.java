package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.construction.minecraft.task.LightAreaTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

public class LightAreaTests {
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

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setEventSink(result);
        return r;
    }

    /** Ticks the number of dark candidates must hold still before a roofed fixture is scanned. */
    private static final int LIGHT_STABLE_TICKS = 10;

    /**
     * Waits until the fixture has a dark spot to light and that count has stopped moving, then drives the task. The
     * value the task reads is the live light of the world, so a fixture still being lit by the threaded light engine
     * would be scanned half-updated; the companion is pinned meanwhile so the task starts from the block the fixture
     * expects.
     */
    private static void driveAfterLightSettles(
            GameTestHelper h,
            CompanionEntity c,
            Result result,
            LightAreaTask task,
            BlockPos min,
            BlockPos max,
            Runnable check) {
        BlockPos base = h.absolutePos(BlockPos.ZERO);
        int[] last = {-1, 0};
        drive(
                h,
                c,
                result,
                task,
                () -> {
                    int dark = darkCandidates(h, base, min, max);
                    if (dark == last[0]) {
                        last[1]++;
                    } else {
                        last[0] = dark;
                        last[1] = 0;
                    }
                    return dark > 0 && last[1] >= LIGHT_STABLE_TICKS;
                },
                check);
    }

    /** How many cells of the box are dark spots the task should light. */
    private static int darkCandidates(GameTestHelper h, BlockPos base, BlockPos min, BlockPos max) {
        int dark = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    if (isDarkSpot(h, base.offset(x, y, z))) {
                        dark++;
                    }
                }
            }
        }
        return dark;
    }

    /**
     * Waits until every candidate cell of an open-sky fixture reads sky light, then drives the task. Waiting on the
     * values themselves is what keeps a freshly cleared platform from still reading as dark when the task scans it.
     */
    private static void driveInOpenSky(
            GameTestHelper h,
            CompanionEntity c,
            Result result,
            LightAreaTask task,
            BlockPos min,
            BlockPos max,
            Runnable check) {
        BlockPos base = h.absolutePos(BlockPos.ZERO);
        drive(h, c, result, task, () -> everyCellSkyLit(h, base, min, max), check);
    }

    private static void drive(
            GameTestHelper h,
            CompanionEntity c,
            Result result,
            LightAreaTask task,
            BooleanSupplier ready,
            Runnable check) {
        ExecutionCoordinator runner = runner(c, result);
        BlockPos home = c.blockPosition();
        boolean[] started = {false};
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            if (!started[0]) {
                if (!ready.getAsBoolean()) {
                    c.setPos(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                    c.setDeltaMovement(0, 0, 0);
                    h.assertTrue(false, "waiting for the light engine to settle");
                }
                started[0] = true;
                runner.start(task, "light_area");
            }
            runner.tick();
            check.run();
        });
    }

    /** Whether every air cell over a sturdy floor in the box reads open-sky light. */
    private static boolean everyCellSkyLit(GameTestHelper h, BlockPos base, BlockPos min, BlockPos max) {
        var level = h.getLevel();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    BlockPos pos = base.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                        continue;
                    }
                    if (level.getBrightness(LightLayer.SKY, pos) == 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Fills the layer under the platform so the scan cannot see a dark crawlspace the companion can walk into. */
    private static void sealGround(GameTestHelper h, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    /** Whether a cell is a monster-spawnable dark spot: air over a sturdy floor, dark and unlit by the sky. */
    private static boolean isDarkSpot(GameTestHelper h, BlockPos pos) {
        var level = h.getLevel();
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && level.getBrightness(LightLayer.BLOCK, pos) < LightAreaTask.DARK_BELOW
                && level.getBrightness(LightLayer.SKY, pos) == 0;
    }

    /**
     * A three-high room carved below the surface. The ground above it already keeps the sky light at zero, so the
     * room is dark from world generation and a test does not depend on the threaded light engine finishing a roof.
     */
    private static void darkRoom(GameTestHelper h) {
        GameTestSupport.keepLoaded(h, -4, -4, 20, 20);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, -13, z, Blocks.STONE);
                h.setBlock(x, -9, z, Blocks.STONE);
                for (int y = -12; y <= -10; y++) {
                    boolean wall = x == 0 || x == 15 || z == 0 || z == 15;
                    h.setBlock(x, y, z, wall ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
    }

    /** The same room, with a stone shelf at y=-11 so y=-10 also has a sturdy floor to light. */
    private static void tallDarkRoom(GameTestHelper h) {
        darkRoom(h);
        for (int x = 5; x <= 8; x++) {
            for (int z = 1; z <= 4; z++) {
                h.setBlock(x, -11, z, Blocks.STONE);
            }
        }
    }

    @GameTest(maxTicks = 600)
    public void lightPlacesNothingUnderOpenSky(GameTestHelper h) {
        // The scan reaches eight blocks in every direction, so the platform is wide enough to keep the whole scan
        // volume over open, sunlit ground; a smaller platform would scan the natural terrain beyond it.
        GameTestSupport.platform(h, 20);
        sealGround(h, 20);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.TORCH, 16));
        Result result = new Result();
        driveInOpenSky(h, c, result, new LightAreaTask(8), new BlockPos(2, 2, 2), new BlockPos(18, 2, 18), () -> {
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "no dark spots within 8 blocks", "summary");
            h.assertValueEqual(c.inventory().countItem(Items.TORCH), 16, "torches spent under open sky");
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 20; z++) {
                    for (int y = 2; y <= 8; y++) {
                        h.assertFalse(
                                h.getBlockState(new BlockPos(x, y, z)).is(Blocks.TORCH),
                                "a torch was placed under open sky at " + x + "," + y + "," + z);
                    }
                }
            }
        });
    }

    /**
     * A two-high corridor that winds around a wall: the first torch goes into the only cell beside the companion,
     * and the cell at the far end is still dark at block light 7 while its Manhattan distance from that torch is
     * five. The old shortcut skipped it as lit; the light paths around the wall instead.
     */
    @GameTest(maxTicks = 1200)
    public void aWallDoesNotHideADarkCell(GameTestHelper h) {
        GameTestSupport.keepLoaded(h, -4, -4, 16, 16);
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                for (int y = -13; y <= -9; y++) {
                    h.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        int[][] corridor = {{2, 2}, {3, 2}, {4, 2}, {4, 3}, {5, 3}, {6, 3}, {6, 2}, {7, 2}, {8, 2}};
        for (int[] cell : corridor) {
            h.setBlock(cell[0], -12, cell[1], Blocks.AIR);
            h.setBlock(cell[0], -11, cell[1], Blocks.AIR);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 16));
        BlockPos origin = c.blockPosition();
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(7), new BlockPos(0, -12, 0), new BlockPos(11, -11, 11), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.SUCCESS,
                            "not finished: " + result.status + " " + result.summary);
                    BlockPos behindTheWall = origin.offset(6, 0, 0);
                    int light = h.getLevel().getBrightness(LightLayer.BLOCK, behindTheWall);
                    h.assertTrue(light >= 8, "the wall hid the dark spot at " + behindTheWall + " with light " + light);
                });
    }

    @GameTest(maxTicks = 1200)
    public void everyScannedLayerIsLit(GameTestHelper h) {
        tallDarkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 24));
        BlockPos origin = c.blockPosition();
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(8), new BlockPos(1, -12, 1), new BlockPos(14, -10, 14), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.SUCCESS,
                            "not finished: " + result.status + " " + result.summary);
                    for (int level = 0; level <= 2; level += 2) {
                        for (int dx = -1; dx <= 8; dx++) {
                            for (int dz = -1; dz <= 8; dz++) {
                                BlockPos pos = origin.offset(dx, level, dz);
                                h.assertFalse(
                                        isDarkSpot(h, pos),
                                        "a dark spot at layer " + level + " (" + dx + "," + dz + ") has light "
                                                + h.getLevel().getBrightness(LightLayer.BLOCK, pos) + "; "
                                                + result.summary);
                            }
                        }
                    }
                });
    }

    @GameTest(maxTicks = 1200)
    public void lightsDarkRoom(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 16));
        BlockPos origin = c.blockPosition();
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(6), new BlockPos(1, -12, 1), new BlockPos(14, -12, 14), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.SUCCESS,
                            "not finished: " + result.status + " " + result.summary);
                    h.assertTrue(result.summary.startsWith("placed "), "summary was " + result.summary);
                    h.assertTrue(
                            result.summary.endsWith("no dark spots left within 6 blocks"),
                            "summary was " + result.summary);
                    for (int x = origin.getX() - 6; x <= origin.getX() + 6; x++) {
                        for (int z = origin.getZ() - 6; z <= origin.getZ() + 6; z++) {
                            BlockPos pos = new BlockPos(x, origin.getY(), z);
                            h.assertFalse(
                                    isDarkSpot(h, pos),
                                    "dark spot at " + pos + " has light "
                                            + h.getLevel().getBrightness(LightLayer.BLOCK, pos));
                        }
                    }
                });
    }

    @GameTest(maxTicks = 200)
    public void lightWithoutTorchesFails(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(8), new BlockPos(1, -12, 1), new BlockPos(14, -12, 14), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
                    h.assertValueEqual(result.summary, "I don't have minecraft:torch", "summary");
                });
    }

    @GameTest(maxTicks = 200)
    public void lightAlreadyLit(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        sealGround(h, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 4, z, Blocks.GLOWSTONE);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new LightAreaTask(8), "light_area");
        int[] wait = {5};
        h.succeedWhen(() -> {
            if (wait[0] > 0) {
                wait[0]--;
                return;
            }
            runner.tick();
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS, "should succeed: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("no dark spots within 8 blocks"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 200)
    public void lightReportsSkippedSpots(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 1));
        c.hands().overrideProtection(p -> true);
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(2), new BlockPos(1, -12, 1), new BlockPos(14, -12, 14), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.FAILED,
                            "not finished: " + result.status + " " + result.summary);
                    h.assertValueEqual(result.summary, "couldn't reach or place any torch within 2 blocks", "summary");
                });
    }

    @GameTest(maxTicks = 1200)
    public void lightUsesATorchInTheMainHand(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        c.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH, 4));
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(6), new BlockPos(1, -12, 1), new BlockPos(14, -12, 14), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.SUCCESS,
                            "not finished: " + result.status + " " + result.summary);
                    h.assertTrue(result.summary.startsWith("placed "), "summary was " + result.summary);
                    h.assertTrue(
                            c.getMainHandItem().getCount() < 4,
                            "the main-hand torches were not used: "
                                    + c.getMainHandItem().getCount() + " left; " + result.summary);
                });
    }

    @GameTest
    public void lightClampsAnUnboundedRadius(GameTestHelper h) {
        String described = new LightAreaTask(1_000_000).describe();
        h.assertTrue(described.equals("lighting up the area within 16 blocks"), "describe was " + described);
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void lightRunsOutOfTorches(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -12, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 2));
        Result result = new Result();
        driveAfterLightSettles(
                h, c, result, new LightAreaTask(8), new BlockPos(1, -12, 1), new BlockPos(14, -12, 14), () -> {
                    h.assertTrue(
                            result.status == TaskStatus.SUCCESS,
                            "not finished: " + result.status + " " + result.summary);
                    h.assertValueEqual(result.summary, "placed 2 torches, then ran out of torches", "summary");
                });
    }
}
