package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.excavation.ExcavationDirection;
import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.features.mining.minecraft.mining.MiningSession;
import dev.friendai.features.mining.minecraft.task.ExcavateTask;
import dev.friendai.features.mining.minecraft.task.MineBlockAtTask;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class ExcavationTests {
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

    private static CompanionEntity companion(GameTestHelper h) {
        GameTestSupport.platform(h, 24);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        return c;
    }

    private static void observe(GameTestHelper h, CompanionEntity c, BlockPos... positions) {
        for (BlockPos pos : positions) {
            h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(pos)), "cell should be visible at " + pos);
        }
    }

    private static int count(GameTestHelper h, Block block, BlockPos... positions) {
        int found = 0;
        for (BlockPos pos : positions) {
            if (h.getLevel().getBlockState(h.absolutePos(pos)).is(block)) {
                found++;
            }
        }
        return found;
    }

    private static ExcavationSpec spec(ExcavationGeometry geometry, int count) {
        return ExcavationSpec.of(geometry, count, null, 0, true, false, false, true, 0)
                .orElseThrow();
    }

    @GameTest(maxTicks = 1600)
    public void boxMinesEveryCellAndCollectsDrops(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos[] cells = {new BlockPos(4, 2, 2), new BlockPos(5, 2, 2), new BlockPos(4, 2, 3), new BlockPos(5, 2, 3)};
        for (BlockPos cell : cells) {
            h.setBlock(cell, Blocks.STONE);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new ExcavateTask(spec(ExcavationGeometry.box(4, 2, 2, 5, 2, 3).orElseThrow(), 4)), "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            if (result.status == null || result.status == TaskStatus.RUNNING) {
                return;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "box failed: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, cells) == 0, "box cells remain: " + result.summary);
            h.assertTrue(
                    c.inventory().countItem(Items.COBBLESTONE) >= 4,
                    "cobblestone collected: " + c.inventory().countItem(Items.COBBLESTONE));
        });
    }

    @GameTest(maxTicks = 1600)
    public void tunnelLeavesAWalkableCorridor(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos[] cells = {
            new BlockPos(4, 2, 2), new BlockPos(4, 3, 2), new BlockPos(5, 2, 2),
            new BlockPos(5, 3, 2), new BlockPos(6, 2, 2), new BlockPos(6, 3, 2)
        };
        for (BlockPos cell : cells) {
            h.setBlock(cell, Blocks.STONE);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new ExcavateTask(spec(
                        ExcavationGeometry.tunnel(ExcavationDirection.EAST, 4, 2, 2, 3, 1, 2)
                                .orElseThrow(),
                        6)),
                "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            if (result.status == null || result.status == TaskStatus.RUNNING) {
                return;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "tunnel failed: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, cells) == 0, "tunnel cells remain: " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void containersAreSkippedByDefault(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos stone = new BlockPos(4, 2, 2);
        BlockPos chest = new BlockPos(5, 2, 2);
        h.setBlock(stone, Blocks.STONE);
        h.setBlock(chest, Blocks.CHEST);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new ExcavateTask(spec(ExcavationGeometry.box(4, 2, 2, 5, 2, 2).orElseThrow(), 2)), "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            if (result.status == null || result.status == TaskStatus.RUNNING) {
                return;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "container job failed: " + result.summary);
            h.assertTrue(count(h, Blocks.CHEST, chest) == 1, "chest was broken: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, stone) == 0, "stone was not mined: " + result.summary);
            h.assertTrue(result.summary.contains("skipped"), "summary should name the skip: " + result.summary);
        });
    }

    @GameTest(maxTicks = 1600)
    public void shaftRefusesTheCellAboveLava(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(4, 1, 2), Blocks.LAVA);
        BlockPos low = new BlockPos(4, 2, 2);
        BlockPos middle = new BlockPos(4, 3, 2);
        BlockPos high = new BlockPos(4, 4, 2);
        h.setBlock(low, Blocks.STONE);
        h.setBlock(middle, Blocks.STONE);
        h.setBlock(high, Blocks.STONE);
        observe(h, c, low, middle, high);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new ExcavateTask(spec(
                        ExcavationGeometry.shaft(ExcavationDirection.DOWN, 4, 4, 2, 3, 1, 1)
                                .orElseThrow(),
                        3)),
                "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            if (result.status == null || result.status == TaskStatus.RUNNING) {
                return;
            }
            h.assertTrue(count(h, Blocks.STONE, low) == 1, "the cell above lava was broken: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, high) == 0, "the safe top cell was not mined: " + result.summary);
            h.assertTrue(!c.isInLava(), "the companion ended in lava");
        });
    }

    @GameTest(maxTicks = 1200)
    public void veinFalseMinesOnlyThePointedBlock(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos pointed = new BlockPos(4, 2, 2);
        BlockPos neighbour = new BlockPos(4, 2, 3);
        h.setBlock(pointed, Blocks.IRON_ORE);
        h.setBlock(neighbour, Blocks.IRON_ORE);
        observe(h, c, pointed, neighbour);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(h.absolutePos(pointed), false, 32, true), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            if (result.status == null || result.status == TaskStatus.RUNNING) {
                return;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(count(h, Blocks.IRON_ORE, pointed) == 0, "pointed ore was not mined: " + result.summary);
            h.assertTrue(count(h, Blocks.IRON_ORE, neighbour) == 1, "vein:false mined a neighbour: " + result.summary);
        });
    }

    @GameTest(maxTicks = 1600)
    public void veinNeighbourAboveLavaIsRefused(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos pointed = new BlockPos(4, 2, 2);
        BlockPos neighbour = new BlockPos(4, 2, 3);
        h.setBlock(pointed, Blocks.IRON_ORE);
        h.setBlock(neighbour, Blocks.IRON_ORE);
        h.setBlock(new BlockPos(4, 1, 3), Blocks.LAVA);
        observe(h, c, pointed, neighbour);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(h.absolutePos(pointed), true, 32, true), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            if (result.status == null || result.status == TaskStatus.RUNNING) {
                return;
            }
            h.assertTrue(count(h, Blocks.IRON_ORE, pointed) == 0, "pointed ore was not mined: " + result.summary);
            h.assertTrue(
                    count(h, Blocks.IRON_ORE, neighbour) == 1,
                    "the neighbour above lava was broken: " + result.summary);
            h.assertTrue(!c.isInLava(), "the companion ended in lava");
        });
    }

    @GameTest(maxTicks = 400)
    public void descentFallbackBreaksTheSupportBelowTheCompanion(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        MiningSession session = new MiningSession(c, new MiningBounds(new Node(2, 1, 2), 8), 8, 600);

        h.assertTrue(session.forceDescent(new TaskContext(c, h.getLevel())), "the descent below the companion starts");
        h.succeedWhen(() -> {
            TaskContext context = new TaskContext(c, h.getLevel());
            MiningSession.State state = session.tick(context);
            if (state == MiningSession.State.EXTRACTED) {
                h.assertTrue(
                        h.getLevel()
                                .getBlockState(h.absolutePos(new BlockPos(2, 1, 2)))
                                .isAir(),
                        "the support under the companion was not broken");
                h.assertTrue(c.blockPosition().getY() <= 1, "the companion did not drop onto the safe landing");
            } else {
                h.assertTrue(state == MiningSession.State.WORKING, "unexpected descent state: " + state);
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void descentFallbackRefusesAnUnbreakableSupport(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(2, 1, 2), Blocks.BEDROCK);
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        MiningSession session = new MiningSession(c, new MiningBounds(new Node(2, 1, 2), 8), 8, 600);

        h.assertFalse(
                session.forceDescent(new TaskContext(c, h.getLevel())),
                "bedrock under the companion must refuse the descent");
        h.assertTrue(
                h.getLevel().getBlockState(h.absolutePos(new BlockPos(2, 1, 2))).is(Blocks.BEDROCK),
                "the refused support changed");
    }
}
