package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.MineBlockAtTask;
import dev.friedai.task.builtin.MineBlocksTask;
import dev.friedai.task.mining.BlockMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class MineBlocksTests {
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

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner r = new TaskRunner(c, () -> 300, () -> false);
        r.setListener(result);
        return r;
    }

    private static MineBlocksTask mine(String block, int count) {
        return new MineBlocksTask(BlockMatcher.parse(block).orElseThrow(), count, 32);
    }

    private static int countBlocks(GameTestHelper h, Block block, BlockPos... positions) {
        int n = 0;
        for (BlockPos p : positions) {
            if (h.getLevel().getBlockState(h.absolutePos(p)).is(block)) {
                n++;
            }
        }
        return n;
    }

    private static final BlockPos[] LOGS = {
        new BlockPos(6, 2, 2), new BlockPos(6, 3, 2), new BlockPos(6, 2, 4), new BlockPos(8, 2, 2), new BlockPos(8, 2, 5)
    };

    private static void placeLogs(GameTestHelper h, int n, Block block) {
        for (int i = 0; i < n; i++) {
            h.setBlock(LOGS[i], block);
        }
    }

    @GameTest(maxTicks = 1200)
    public void minesRequestedLogs(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        placeLogs(h, 5, Blocks.ACACIA_LOG);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(mine("#minecraft:acacia_logs", 3), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.ACACIA_LOG) >= 3, "logs collected: " + c.inventory().countItem(Items.ACACIA_LOG));
            h.assertTrue(countBlocks(h, Blocks.ACACIA_LOG, LOGS) == 2, "logs left: " + countBlocks(h, Blocks.ACACIA_LOG, LOGS));
        });
    }

    @GameTest(maxTicks = 1200)
    public void partialWhenNotEnough(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        placeLogs(h, 2, Blocks.BIRCH_LOG);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(mine("minecraft:birch_log", 5), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished");
            h.assertTrue(result.summary.startsWith("mined 2 of 5"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 200)
    public void failsWhenNoneNearby(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(mine("minecraft:spruce_log", 3), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished");
            h.assertTrue(result.summary.equals("no minecraft:spruce_log nearby"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 200)
    public void skipsUnbreakable(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos bedrock = new BlockPos(4, 2, 2);
        h.setBlock(bedrock, Blocks.BEDROCK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(mine("minecraft:bedrock", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished");
            h.assertTrue(result.summary.equals("no minecraft:bedrock nearby"), "summary was " + result.summary);
            h.assertTrue(countBlocks(h, Blocks.BEDROCK, bedrock) == 1, "bedrock must stay");
        });
    }

    @GameTest(maxTicks = 600)
    public void equipsBestTool(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.JUNGLE_LOG);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.STONE_AXE));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(mine("minecraft:jungle_log", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished");
            h.assertTrue(c.getMainHandItem().is(Items.STONE_AXE), "main hand holds " + c.getMainHandItem());
        });
    }

    @GameTest
    public void unknownBlockDoesNotParse(GameTestHelper h) {
        h.assertTrue(BlockMatcher.parse("nonsense:block").isEmpty(), "unknown block must not parse");
        h.assertTrue(BlockMatcher.parse("oak_log").isPresent(), "default namespace should be minecraft");
        h.assertTrue(BlockMatcher.parse("#minecraft:logs").isPresent(), "tags should parse");
        h.succeed();
    }

    private static String coords(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // Emerald is 500 ticks bare-handed (no correct tool drops), so the budget must cover the walk plus the break.
    @GameTest(maxTicks = 1200)
    public void mineAtBreaksTargetAndCollects(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.EMERALD_BLOCK);
        h.setBlock(6, 2, 4, Blocks.EMERALD_BLOCK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("mined minecraft:emerald_block at " + coords(target)), "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 4)).is(Blocks.EMERALD_BLOCK), "the other block must stay");
            h.assertTrue(c.inventory().countItem(Items.EMERALD_BLOCK) == 1, "drops collected: " + c.inventory().countItem(Items.EMERALD_BLOCK));
        });
    }

    @GameTest(maxTicks = 600)
    public void mineAtChangedBlockFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.LAPIS_BLOCK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            if (++ticks[0] == 5) {
                h.setBlock(6, 2, 2, Blocks.GOLD_BLOCK);
            }
            h.assertTrue(ticks[0] >= 6, "waiting for the block change");
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("the block at ") && result.summary.endsWith(" changed before I could mine it"),
                    "summary was " + result.summary);
        });
    }

    @GameTest
    public void mineAtAirFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.equals("there is no block at " + coords(target)), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void mineAtUnbreakableFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.BEDROCK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.equals("minecraft:bedrock at " + coords(target) + " can't be broken"), "summary was " + result.summary);
        h.succeed();
    }
}
