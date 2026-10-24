package dev.friendai.test;

import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.LogEntry;
import dev.friendai.debug.LogKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.pathing.Cell;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.task.mining.BreakProgress;
import dev.friendai.task.nav.LevelCellView;
import dev.friendai.task.nav.MaterialPicker;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PathingTests {

    private static Cell cellAt(LevelCellView view, GameTestHelper h, int x, int y, int z) {
        return cellAt(view, h.absolutePos(new BlockPos(x, y, z)));
    }

    private static Cell cellAt(LevelCellView view, BlockPos absolute) {
        return view.at(absolute.getX(), absolute.getY(), absolute.getZ());
    }

    @GameTest
    public void cellViewClassifiesBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        h.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        h.setBlock(new BlockPos(3, 2, 2), Blocks.OAK_PLANKS);
        h.setBlock(new BlockPos(4, 2, 2), Blocks.WATER);
        h.setBlock(new BlockPos(5, 2, 2), Blocks.MAGMA_BLOCK);
        h.setBlock(new BlockPos(6, 2, 2), Blocks.STONE_SLAB);
        h.setBlock(new BlockPos(2, 2, 5), Blocks.OAK_FENCE);
        h.setBlock(new BlockPos(3, 2, 5), Blocks.SCAFFOLDING);
        h.setBlock(new BlockPos(4, 2, 5), Blocks.OAK_LOG);

        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        LevelCellView bare = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);

        h.assertTrue(cellAt(bare, h, 2, 2, 2).kind() == Cell.Kind.SOLID, "stone");
        h.assertFalse(cellAt(bare, h, 3, 2, 2).breakable(), "planks are blacklisted");
        h.assertTrue(cellAt(bare, h, 4, 2, 2).kind() == Cell.Kind.WATER, "water");
        h.assertTrue(cellAt(bare, h, 4, 2, 2).placeable(), "water can be filled");
        h.assertTrue(cellAt(bare, h, 5, 2, 2).kind() == Cell.Kind.HAZARD, "magma");
        h.assertTrue(cellAt(bare, h, 6, 2, 2).kind() == Cell.Kind.PARTIAL, "slab");
        h.assertTrue(cellAt(bare, h, 2, 2, 5).kind() == Cell.Kind.TALL, "fence");
        h.assertTrue(cellAt(bare, h, 3, 2, 5).kind() == Cell.Kind.CLIMBABLE, "scaffolding");

        BlockPos logPos = h.absolutePos(new BlockPos(4, 2, 5));
        BlockState log = h.getLevel().getBlockState(logPos);
        int expected = BreakProgress.ticksToBreak(log.getDestroySpeed(h.getLevel(), logPos), 1f, true)
                + CostModel.DEFAULTS.logPenalty();
        h.assertTrue(cellAt(bare, logPos).breakTicks() == expected,
                "log ticks " + cellAt(bare, logPos).breakTicks() + " != " + expected);

        int bareStone = cellAt(bare, h.absolutePos(new BlockPos(2, 2, 2))).breakTicks();
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        LevelCellView armed = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);
        h.assertTrue(cellAt(armed, h.absolutePos(new BlockPos(2, 2, 2))).breakTicks() < bareStone,
                "a pickaxe breaks stone faster");
        h.succeed();
    }

    @GameTest
    public void materialPickerPrefersDirt(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.inventory().addItem(new ItemStack(Items.DIRT, 5));
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
        c.inventory().addItem(new ItemStack(Items.SAND, 4));
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 2));

        MaterialPicker picker = new MaterialPicker(PathingConfig.DEFAULTS);
        h.assertTrue(picker.count(c.inventory()) == 7, "count " + picker.count(c.inventory()));
        h.assertTrue(picker.cheapestPrice(c.inventory()) == 1.0, "price " + picker.cheapestPrice(c.inventory()));
        Optional<ItemStack> taken = picker.takeCheapest(c.inventory());
        h.assertTrue(taken.isPresent(), "nothing taken");
        h.assertTrue(taken.get().is(Items.DIRT), "took " + taken.get());
        h.assertTrue(c.inventory().countItem(Items.DIRT) == 4, "dirt left " + c.inventory().countItem(Items.DIRT));
        h.succeed();
    }

    @GameTest
    public void protectedWallIsNotDug(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        int wallX = h.absolutePos(new BlockPos(5, 2, 0)).getX();
        for (int z = 0; z < 8; z++) {
            for (int y = 2; y <= 3; y++) {
                h.setBlock(new BlockPos(5, y, z), Blocks.STONE);
            }
        }
        h.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.hands().overrideProtection(p -> p.getX() == wallX);

        LevelCellView view = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);
        for (int z = 0; z < 8; z++) {
            for (int y = 2; y <= 3; y++) {
                h.assertTrue(cellAt(view, h, 5, y, z).breakTicks() == -1, "wall at y=" + y + " z=" + z);
            }
        }
        h.assertTrue(cellAt(view, h, 2, 2, 2).breakTicks() > 0, "stone outside the wall stays breakable");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void taskDebugReachesLog(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The task listener is installed with the owner's brain entry, as it already is for notices.
        brains.currentPrompt(player.getUUID());
        c.taskRunner().debug("path test");
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
        h.assertTrue(log.stream().anyMatch(e -> e.kind() == LogKind.TASK && e.text().contains("path test")),
                "debug line must reach the owner's log: " + log);
        h.succeed();
    }
}
