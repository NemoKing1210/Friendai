package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.construction.minecraft.task.LightAreaTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
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

    private static void darkRoom(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 5, z, Blocks.STONE);
                if (x == 0 || x == 15 || z == 0 || z == 15) {
                    for (int y = 2; y <= 4; y++) {
                        h.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
    }

    @GameTest(maxTicks = 1200)
    public void lightsDarkRoom(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 16));
        BlockPos origin = c.blockPosition();
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new LightAreaTask(6), "light_area");
        int[] after = {-1};
        h.succeedWhen(() -> {
            if (after[0] < 0) {
                runner.tick();
                h.assertTrue(result.status != TaskStatus.FAILED, "failed: " + result.summary);
                if (result.status == TaskStatus.SUCCESS) {
                    h.assertTrue(result.summary.startsWith("placed "), "summary was " + result.summary);
                    h.assertTrue(
                            result.summary.endsWith("no dark spots left within 6 blocks"),
                            "summary was " + result.summary);
                    after[0] = 20;
                }
                return;
            }
            if (after[0] > 0) {
                after[0]--;
                return;
            }
            for (int x = origin.getX() - 6; x <= origin.getX() + 6; x++) {
                for (int z = origin.getZ() - 6; z <= origin.getZ() + 6; z++) {
                    BlockPos pos = new BlockPos(x, origin.getY(), z);
                    if (!h.getLevel().getBlockState(pos).isAir()
                            || h.getLevel().getBlockState(pos.below()).isAir()) {
                        continue;
                    }
                    int light = h.getLevel().getBrightness(LightLayer.BLOCK, pos);
                    h.assertTrue(light >= 8, "dark spot at " + pos + " has light " + light);
                }
            }
        });
    }

    @GameTest
    public void lightWithoutTorchesFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new LightAreaTask(8), "light_area");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(result.summary.equals("I don't have minecraft:torch"), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void lightAlreadyLit(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
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
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 1));
        c.hands().overrideProtection(p -> true);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new LightAreaTask(2), "light_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("couldn't reach or place any torch within 2 blocks"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void lightRunsOutOfTorches(GameTestHelper h) {
        darkRoom(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH, 2));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new LightAreaTask(8), "light_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("placed 2 torches, then ran out of torches"),
                    "summary was " + result.summary);
        });
    }
}
