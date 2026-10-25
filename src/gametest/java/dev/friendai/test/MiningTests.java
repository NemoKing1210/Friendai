package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.mining.MiningBounds;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.mining.BreakAction;
import dev.friendai.task.mining.ExcavationGuard;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class MiningTests {

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

    private static ExcavationGuard guard(CompanionEntity companion, GameTestHelper h) {
        BlockPos start = companion.blockPosition();
        return new ExcavationGuard(new MiningBounds(new Node(start.getX(), start.getY(), start.getZ()), 8), Set.of());
    }
}
