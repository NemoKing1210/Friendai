package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.nav.Approach;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class InteractTests {
    @GameTest(maxTicks = 400)
    public void approachReachesBlock(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(10, 2, 2));
        Approach approach = new Approach(target);
        h.succeedWhen(() -> {
            Approach.State state = approach.tick(c);
            h.assertTrue(state == Approach.State.IN_REACH, "state was " + state);
            double distance = c.getEyePosition().distanceTo(Vec3.atCenterOf(target));
            h.assertTrue(distance <= Approach.REACH, "eye distance was " + distance);
        });
    }

    @GameTest(maxTicks = 400)
    public void approachReportsUnreachable(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos targetRel = new BlockPos(10, 2, 10);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    h.setBlock(targetRel.offset(dx, dy, dz), Blocks.TINTED_GLASS);
                }
            }
        }
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(targetRel);
        Approach approach = new Approach(target);
        h.succeedWhen(() -> {
            Approach.State state = approach.tick(c);
            h.assertTrue(state == Approach.State.UNREACHABLE, "state was " + state);
        });
    }

    @GameTest
    public void handsReturnLeftoverAndStayEmpty(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 4, 2, 4);
        BlockPos against = h.absolutePos(new BlockPos(5, 1, 5));
        InteractionResult result = c.hands().place(new ItemStack(Items.MOSSY_COBBLESTONE, 3), against, Direction.UP);
        h.assertTrue(result.consumesAction(), "placement result was " + result);
        h.assertTrue(h.getBlockState(new BlockPos(5, 2, 5)).is(Blocks.MOSSY_COBBLESTONE), "block must be placed");
        h.assertTrue(c.inventory().countItem(Items.MOSSY_COBBLESTONE) == 2,
                "leftover was " + c.inventory().countItem(Items.MOSSY_COBBLESTONE));
        h.assertTrue(c.hands().player().getInventory().isEmpty(), "the fake player inventory must be empty");
        h.succeed();
    }

    @GameTest
    public void handsPlaceOnOccupiedReturnsEverything(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.STONE_BRICKS);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 4, 2, 4);
        BlockPos against = h.absolutePos(new BlockPos(5, 1, 5));
        InteractionResult result = c.hands().place(new ItemStack(Items.CRACKED_STONE_BRICKS), against, Direction.UP);
        h.assertTrue(!result.consumesAction(), "placement result was " + result);
        h.assertTrue(c.inventory().countItem(Items.CRACKED_STONE_BRICKS) == 1,
                "item must come back, have " + c.inventory().countItem(Items.CRACKED_STONE_BRICKS));
        h.assertTrue(c.hands().player().getInventory().isEmpty(), "the fake player inventory must be empty");
        h.succeed();
    }
}
