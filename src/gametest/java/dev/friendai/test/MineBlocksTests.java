package dev.friendai.test;

import dev.friendai.debug.DebugAction;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.MineBlockAtTask;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.mining.BlockMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
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
        new BlockPos(6, 2, 2),
        new BlockPos(6, 3, 2),
        new BlockPos(6, 2, 4),
        new BlockPos(8, 2, 2),
        new BlockPos(8, 2, 5)
    };

    private static void placeLogs(GameTestHelper h, int n, Block block) {
        for (int i = 0; i < n; i++) {
            h.setBlock(LOGS[i], block);
        }
    }

    @GameTest(maxTicks = 200)
    public void veinWorkSetIncludesAllSixVisibleOreBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        java.util.List<BlockPos> vein = new java.util.ArrayList<>();
        for (int z = 2; z < 8; z++) {
            BlockPos pos = new BlockPos(6, 2, z);
            h.setBlock(pos, Blocks.COAL_ORE);
            BlockPos absolute = h.absolutePos(pos);
            h.assertTrue(GameTestSupport.observeVisible(h, c, absolute), "ore should be visible at " + pos);
            vein.add(absolute);
        }
        MineBlocksTask task = mine("minecraft:coal_ore", 6);
        ExecutionCoordinator runner = runner(c, new Result());
        runner.start(task, "mine_blocks");
        runner.tick();
        var work = task.debugWorkSets().getFirst();
        h.assertValueEqual(work.total(), 6, "vein work-set total");
        h.assertTrue(
                vein.stream()
                        .allMatch(pos -> work.cells().stream()
                                .anyMatch(cell ->
                                        cell.x() == pos.getX() && cell.y() == pos.getY() && cell.z() == pos.getZ())),
                "work set should include all planned vein blocks: " + work.cells());
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void minesRequestedLogs(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] targets = {
            new BlockPos(5, 2, 3),
            new BlockPos(4, 2, 8),
            new BlockPos(8, 2, 5),
            new BlockPos(10, 2, 9),
            new BlockPos(11, 2, 2)
        };
        for (BlockPos target : targets) h.setBlock(target, Blocks.ACACIA_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        for (BlockPos relative : new BlockPos[] {targets[0], targets[1], targets[2]}) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(relative)),
                    "requested log should be directly visible at " + relative);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("#minecraft:acacia_logs", 3), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    c.inventory().countItem(Items.ACACIA_LOG) >= 3,
                    "logs collected: " + c.inventory().countItem(Items.ACACIA_LOG));
            h.assertTrue(
                    countBlocks(h, Blocks.ACACIA_LOG, targets) == 2,
                    "logs left: " + countBlocks(h, Blocks.ACACIA_LOG, targets));
        });
    }

    @GameTest(maxTicks = 1200)
    public void partialWhenNotEnough(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        placeLogs(h, 2, Blocks.BIRCH_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(LOGS[0])), "birch log should be visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(LOGS[1])), "upper birch log should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(bedrock)), "bedrock should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 2, 2))),
                "jungle log should be visible");
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.STONE_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
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

    @GameTest(maxTicks = 100)
    public void miningSearchExposesSelectedBlockCandidates(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(6, 2, 2);
        BlockPos target = h.absolutePos(relative);
        h.setBlock(relative, Blocks.ACACIA_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        h.assertTrue(GameTestSupport.observeVisible(h, c, target), "target log should be visible");
        MineBlocksTask task = mine("minecraft:acacia_log", 1);
        ExecutionCoordinator runner = runner(c, new Result());
        runner.start(task, "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    task.debugWorkSets().stream()
                            .flatMap(set -> set.cells().stream())
                            .anyMatch(cell -> cell.x() == target.getX()
                                    && cell.y() == target.getY()
                                    && cell.z() == target.getZ()),
                    "task work set should contain the live mining candidate");
            var targets = task.debugTargets();
            h.assertTrue(
                    targets.stream()
                            .anyMatch(debug -> debug.action() == DebugAction.BREAK
                                    && debug.x() == target.getX()
                                    && debug.y() == target.getY()
                                    && debug.z() == target.getZ()),
                    "selected mine candidate missing: " + targets);
        });
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // An emerald block only drops with an iron pickaxe or better, as in vanilla.
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, c, target), "emerald target should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("mined minecraft:emerald_block at " + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 4)).is(Blocks.EMERALD_BLOCK), "the other block must stay");
            h.assertTrue(
                    c.inventory().countItem(Items.EMERALD_BLOCK) == 1,
                    "drops collected: " + c.inventory().countItem(Items.EMERALD_BLOCK));
        });
    }

    @GameTest(maxTicks = 600)
    public void mineAtChangedBlockFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.LAPIS_BLOCK);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, c, target), "initial lapis block should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            if (++ticks[0] == 5) {
                h.setBlock(6, 2, 2, Blocks.GOLD_BLOCK);
            }
            h.assertTrue(ticks[0] >= 6, "waiting for the block change");
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("the block at ")
                            && result.summary.endsWith(" changed before I could mine it"),
                    "summary was " + result.summary);
        });
    }

    @GameTest
    public void mineAtAirFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, c, target), "bedrock target should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(
                result.summary.equals("minecraft:bedrock at " + coords(target) + " can't be broken"),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void mineAtProtectedFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.PRISMARINE_BRICKS);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        c.hands().overrideProtection(p -> p.equals(target));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("is protected by the server"),
                    "summary must name the server: " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 2)).is(Blocks.PRISMARINE_BRICKS), "block must stay");
        });
    }

    @GameTest(maxTicks = 1200)
    public void mineBlocksSkipsProtected(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.DARK_PRISMARINE);
        h.setBlock(10, 2, 5, Blocks.DARK_PRISMARINE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 2))),
                "near protected block should be visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(10, 2, 5))),
                "farther block should be visible");
        BlockPos nearer = h.absolutePos(new BlockPos(6, 2, 2));
        c.hands().overrideProtection(p -> p.equals(nearer));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:dark_prismarine", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 2, 2)).is(Blocks.DARK_PRISMARINE), "the protected block must stay");
            h.assertTrue(h.getBlockState(new BlockPos(10, 2, 5)).is(Blocks.AIR), "the farther block must be mined");
        });
    }

    /** A block hidden inside a hill stays unknown until excavation exposes it. */
    @GameTest(maxTicks = 1200)
    public void minesBlockBuriedInAHill(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK);
                }
            }
        }
        h.setBlock(new BlockPos(7, 3, 7), Blocks.GRANITE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:granite", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status == TaskStatus.FAILED,
                    "hidden granite must stay unknown: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("no minecraft:granite nearby"), "summary was " + result.summary);
            h.assertTrue(
                    countBlocks(h, Blocks.GRANITE, new BlockPos(7, 3, 7)) == 1,
                    "the hidden granite must stay in place");
        });
    }

    /**
     * Two trunks side by side: the companion must finish the tree it started on before moving to the other one, so
     * the four logs of one tree are gone while the other tree stands untouched.
     */
    @GameTest(maxTicks = 1600)
    public void fellsOneTreeBeforeTheNext(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 6; y++) {
            h.setBlock(new BlockPos(7, y, 7), Blocks.OAK_LOG);
            h.setBlock(new BlockPos(10, y, 3), Blocks.OAK_LOG);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(7, 2, 7))),
                "first tree should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(10, 2, 3))),
                "second tree should be directly visible");
        // Dirt to pillar up the trunk: the upper logs are out of reach from the ground.
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:oak_log", 5), "mine_blocks");
        h.succeedWhen(() -> {
            for (int y = 2; y <= 6; y++) {
                if (h.getBlockState(new BlockPos(7, y, 7)).is(Blocks.OAK_LOG))
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(7, y, 7)));
                if (h.getBlockState(new BlockPos(10, y, 3)).is(Blocks.OAK_LOG))
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(10, y, 3)));
            }
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            int firstTree = 0;
            for (int y = 2; y <= 6; y++) {
                if (h.getBlockState(new BlockPos(7, y, 7)).is(Blocks.OAK_LOG)) {
                    firstTree++;
                }
            }
            h.assertTrue(firstTree == 0, "the first tree still has " + firstTree + " logs");
        });
    }

    /** Persistent leaves, so they do not decay while the test waits. */
    private static void leaf(GameTestHelper h, int x, int y, int z) {
        h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
    }

    /** A log with every face but the floor covered: no standing spot can see it. */
    private static void buryLog(GameTestHelper h) {
        h.setBlock(new BlockPos(6, 2, 2), Blocks.OAK_LOG);
        leaf(h, 6, 3, 2);
        leaf(h, 5, 2, 2);
        leaf(h, 7, 2, 2);
        leaf(h, 6, 2, 1);
        leaf(h, 6, 2, 3);
    }

    /** A hidden log is not discoverable through leaves without a separate task first exposing it. */
    @GameTest(maxTicks = 1200)
    public void digsLeavesToTheHiddenLog(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        buryLog(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:oak_log", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status == TaskStatus.FAILED,
                    "hidden log must stay unknown: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("no minecraft:oak_log nearby"), "summary was " + result.summary);
            h.assertTrue(
                    countBlocks(h, Blocks.OAK_LOG, new BlockPos(6, 2, 2)) == 1, "the hidden log must stay in place");
        });
    }

    @GameTest(maxTicks = 600)
    public void mineAtHiddenLogFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        buryLog(h);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals(coords(target) + " is hidden behind another block"),
                    "summary was " + result.summary);
            h.assertTrue(countBlocks(h, Blocks.OAK_LOG, new BlockPos(6, 2, 2)) == 1, "the hidden log must stay");
        });
    }

    /** Leaves cover one side only: the companion must step around them instead of mining through them. */
    @GameTest(maxTicks = 1200)
    public void minesAroundLeavesInTheWay(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(6, 2, 2), Blocks.OAK_LOG);
        leaf(h, 5, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 8);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 2))),
                "log should be visible around the leaf");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:oak_log", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(countBlocks(h, Blocks.OAK_LOG, new BlockPos(6, 2, 2)) == 0, "the log must be mined");
            h.assertTrue(h.getBlockState(new BlockPos(5, 2, 2)).is(Blocks.OAK_LEAVES), "the leaves must stay");
        });
    }

    /**
     * A mined block drops its item the way a player's does: it lands on the ground and lies there for the vanilla
     * pickup delay before the companion takes it, instead of vanishing into the inventory on the break tick.
     */
    @GameTest(maxTicks = 600)
    public void minedDropFallsBeforeItIsCollected(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos log = new BlockPos(6, 2, 2);
        h.setBlock(log, Blocks.ACACIA_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(log)), "log should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:acacia_log", 1), "mine_blocks");
        int[] ticks = {0};
        int[] brokenAt = {-1};
        boolean[] sawItem = {false};
        h.succeedWhen(() -> {
            ticks[0]++;
            runner.tick();
            if (h.getLevel().getBlockState(h.absolutePos(log)).isAir()) {
                if (brokenAt[0] < 0) {
                    brokenAt[0] = ticks[0];
                }
                if (!h.getLevel()
                        .getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(log)).inflate(2.0))
                        .isEmpty()) {
                    sawItem[0] = true;
                }
                if (ticks[0] - brokenAt[0] < 8) {
                    h.assertValueEqual(
                            c.inventory().countItem(Items.ACACIA_LOG), 0, "the drop was taken on the break tick");
                }
            }
            if (result.status != null) {
                h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
                h.assertValueEqual(c.inventory().countItem(Items.ACACIA_LOG), 1, "the mined log must be collected");
                h.assertTrue(sawItem[0], "no item entity was seen on the ground");
            }
        });
    }

    /**
     * A drop the companion can never reach must not hold up the job: the mined log still counts and the task still
     * finishes, leaving the sealed drop where it lies.
     */
    @GameTest(maxTicks = 1200)
    public void pickupDoesNotStallMining(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos log = new BlockPos(6, 2, 2);
        h.setBlock(log, Blocks.ACACIA_LOG);
        sealDrop(h, 10, 3, 2);
        dropItem(h, 10.5, 3.0, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(log)), "log should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:acacia_log", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.ACACIA_LOG), 1, "the mined log must be collected");
            h.assertValueEqual(sealedItems(h), 3, "the sealed drop must stay on the ground");
        });
    }

    /** A stone box around one cell, so a drop inside it can be seen by the search but never reached. */
    private static void sealDrop(GameTestHelper h, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    h.setBlock(
                            new BlockPos(x + dx, y + dy, z + dz),
                            dx == 0 && dy == 0 && dz == 0 ? Blocks.AIR : Blocks.STONE);
                }
            }
        }
    }

    private static int sealedItems(GameTestHelper h) {
        return h
                .getLevel()
                .getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(new BlockPos(10, 3, 2))))
                .stream()
                .filter(e -> e.getItem().is(Items.COBBLESTONE))
                .mapToInt(e -> e.getItem().getCount())
                .sum();
    }

    private static ItemEntity dropItem(GameTestHelper h, double x, double y, double z, ItemStack stack) {
        Vec3 p = h.absoluteVec(new Vec3(x, y, z));
        ItemEntity e = new ItemEntity(h.getLevel(), p.x, p.y, p.z, stack);
        e.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(e);
        return e;
    }

    @GameTest(maxTicks = 200)
    public void mineLogsStopsBeforeDroppingWhenFull(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos log = new BlockPos(6, 2, 2);
        h.setBlock(log, Blocks.ACACIA_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(log)), "log should be directly visible");
        for (int i = 0; i < 27; i++) {
            c.inventory().addItem(new ItemStack(Items.DIRT, 64));
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(mine("minecraft:acacia_log", 1), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(log).is(Blocks.ACACIA_LOG), "the log must not be broken without room");
            h.assertTrue(result.summary.contains("inventory full"), result.summary);
        });
    }
}
