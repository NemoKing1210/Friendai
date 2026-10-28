package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.placed.PlacedBlock;
import dev.friendai.placed.PlacedBook;
import dev.friendai.placed.PlacedService;
import dev.friendai.task.mining.BlockMatcher;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class PlacedBlockTests {

    private static final BlockPos[] LOG_CELLS = {
        new BlockPos(4, 2, 2), new BlockPos(3, 2, 3), new BlockPos(4, 2, 4)
    };

    private static PlacedBook book(GameTestHelper h, UUID owner) {
        return FriendaiServerState.get(h.getLevel().getServer()).placed().book(owner);
    }

    @GameTest(maxTicks = 200)
    public void recordsPlacementsAndPrunesRemovedBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        for (BlockPos cell : LOG_CELLS) {
            BlockPos target = h.absolutePos(cell);
            c.hands().place(new ItemStack(Items.OAK_LOG), target.below(), Direction.UP);
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.OAK_LOG), "log was not placed at " + cell);
        }
        PlacedBook book = book(h, owner);
        h.assertTrue(book.size() == 3, "ledger size was " + book.size() + ": " + book.all());
        List<PlacedBlock> entries = book.all();
        for (PlacedBlock entry : entries) {
            h.assertTrue(entry.blockId().equals("minecraft:oak_log"), "block was " + entry.blockId());
            h.assertTrue(entry.dimension().equals(h.getLevel().dimension().identifier().toString()),
                    "dimension was " + entry.dimension());
            h.assertTrue(entry.source().equals("direct"), "source was " + entry.source());
        }

        h.setBlock(LOG_CELLS[0], Blocks.AIR);
        BlockMatcher logs = BlockMatcher.parse("minecraft:oak_log").orElseThrow();
        var found = PlacedService.find(c, logs, 16, 8);
        h.assertTrue(found.total() == 2, "after pruning the ledger reported " + found.total() + ": " + found.entries());
        h.assertTrue(book.size() == 2, "book still holds " + book.size());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void failedPlacementLeavesNoEntry(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        BlockPos occupied = h.absolutePos(new BlockPos(4, 2, 2));
        h.setBlock(new BlockPos(4, 2, 2), Blocks.BRICKS);
        c.hands().place(new ItemStack(Items.OAK_LOG), occupied.below(), Direction.UP);
        h.assertTrue(book(h, owner).all().isEmpty(), "a failed placement was recorded: " + book(h, owner).all());
        h.succeed();
    }
}
