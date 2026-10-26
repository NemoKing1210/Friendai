package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.mining.MiningBounds;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.mining.BreakAction;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.ExcavationGuard;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class MiningTests {

    private static final class Outcome implements TaskListener {
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

    @GameTest(maxTicks = 20)
    public void singleBlockMiningRefusesOccupiedSupport(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos support = h.absolutePos(new BlockPos(2, 1, 2));

        BreakAction.State state = new BreakAction(support).tick(new TaskContext(companion, h.getLevel()), block -> true);

        h.assertTrue(state == BreakAction.State.BLOCKED, "occupied support was not refused: " + state);
        h.assertTrue(h.getLevel().getBlockState(support).is(Blocks.STONE), "occupied support was destroyed");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void breakRefusalIsNotCountedAsMined(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(4, 2, 2));
        h.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        BreakAction action = new BreakAction(target);
        TaskContext context = new TaskContext(companion, h.getLevel());

        BreakAction.State first = action.tick(context, block -> !block.isAir());
        companion.hands().overrideProtection(pos -> pos.equals(target));
        BreakAction.State refused = action.tick(context, block -> !block.isAir());

        h.assertTrue(first == BreakAction.State.WORKING, "break did not start: " + first);
        h.assertTrue(refused == BreakAction.State.BLOCKED, "protection refusal counted as mined: " + refused);
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "protected target was destroyed");
        action.stop(context);
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void lostToolStopsOreExtraction(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(3, 2, 2));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.DIAMOND_ORE);
        ExcavationGuard guard = guard(companion, h);

        h.assertFalse(guard.allowTarget(new TaskContext(companion, h.getLevel()), target),
                "ore extraction accepted without a tool that drops ore");
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        h.assertTrue(guard.allowTarget(new TaskContext(companion, h.getLevel()), target),
                "suitable ore tool was not accepted");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void waterloggedNeighborRejectsCorridorBreak(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(4, 2, 2));
        h.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        h.setBlock(new BlockPos(5, 2, 2), Blocks.WATER);
        PathStep step = new PathStep(new Node(target.getX() - 1, target.getY(), target.getZ()),
                new Node(target.getX(), target.getY(), target.getZ()), Move.DIG,
                List.of(new Pos(target.getX(), target.getY(), target.getZ())), List.of(), 1, 0);

        h.assertFalse(guard(companion, h).allowBreak(new TaskContext(companion, h.getLevel()), step,
                new Pos(target.getX(), target.getY(), target.getZ())), "corridor break next to water was allowed");
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "corridor block changed");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void oreBelowFeetUsesSideStance(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        BlockPos ore = new BlockPos(4, 1, 4);
        h.setBlock(4, 0, 4, Blocks.STONE);
        h.setBlock(ore, Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        h.assertTrue(GameTestSupport.observeVisible(h, companion, h.absolutePos(ore)),
                "diamond ore below the companion should be directly visible");
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        Outcome outcome = new Outcome();
        TaskRunner runner = new TaskRunner(companion, () -> 300, () -> false);
        runner.setListener(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:diamond_ore").orElseThrow(), 1, 8),
                "mine_blocks");

        h.succeedWhen(() -> {
            runner.tick();
            if (outcome.status != TaskStatus.SUCCESS || companion.inventory().countItem(Items.DIAMOND) < 1) {
                return;
            }
            h.assertTrue(outcome.status == TaskStatus.SUCCESS,
                    "mining did not finish: " + outcome.status + " " + outcome.summary);
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(ore)).isAir(), "ore support was not extracted");
            h.assertTrue(companion.inventory().countItem(Items.DIAMOND) >= 1, "ore drop was not collected");
            h.assertFalse(companion.blockPosition().equals(h.absolutePos(ore).above()),
                    "companion stayed on the ore support while mining it");
        });
    }

    @GameTest(maxTicks = 100)
    public void inventoryFullStopsBeforeOreExtraction(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        BlockPos ore = new BlockPos(5, 2, 3);
        h.setBlock(ore, Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
        h.assertTrue(GameTestSupport.observeVisible(h, companion, h.absolutePos(ore)),
                "diamond ore should be directly visible");
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, Items.IRON_PICKAXE.getDefaultInstance());
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            companion.inventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        Outcome outcome = new Outcome();
        TaskRunner runner = new TaskRunner(companion, () -> 300, () -> false);
        runner.setListener(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:diamond_ore").orElseThrow(), 1, 8),
                "mine_blocks");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status == TaskStatus.FAILED, "full inventory did not stop mining");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(ore)).is(Blocks.DIAMOND_ORE),
                    "ore was destroyed before checking room for drops");
            h.assertTrue(outcome.summary.contains("inventory full"), "failure did not report full inventory");
        });
    }

    private static ExcavationGuard guard(CompanionEntity companion, GameTestHelper h) {
        BlockPos start = companion.blockPosition();
        return new ExcavationGuard(new MiningBounds(new Node(start.getX(), start.getY(), start.getZ()), 8), Set.of());
    }
}
