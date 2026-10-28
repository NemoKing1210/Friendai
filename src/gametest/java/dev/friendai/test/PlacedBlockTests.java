package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.inventory.minecraft.task.PickupItemsTask;
import dev.friendai.features.inventory.minecraft.task.StoreItemsTask;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.task.MineBlocksTask;
import dev.friendai.features.placements.PlacedBlock;
import dev.friendai.features.placements.PlacedBook;
import dev.friendai.placed.PlacedService;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class PlacedBlockTests {

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
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setListener(result);
        return runner;
    }

    private static final BlockPos[] LOG_CELLS = {new BlockPos(4, 2, 2), new BlockPos(3, 2, 3), new BlockPos(4, 2, 4)};

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
            h.assertTrue(
                    entry.dimension()
                            .equals(h.getLevel().dimension().identifier().toString()),
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
    public void findPlacedListsOnlyOwnBlocksAndGuestsAreRefused(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        BlockPos logCell = h.absolutePos(new BlockPos(4, 2, 2));
        BlockPos chestCell = h.absolutePos(new BlockPos(3, 2, 3));
        c.hands().place(new ItemStack(Items.OAK_LOG), logCell.below(), Direction.UP);
        c.hands().place(new ItemStack(Items.CHEST), chestCell.below(), Direction.UP);
        h.assertTrue(h.getLevel().getBlockState(logCell).is(Blocks.OAK_LOG), "log missing");
        h.assertTrue(h.getLevel().getBlockState(chestCell).is(Blocks.CHEST), "chest missing");

        var tools = dev.friendai.tool.builtin.FriendaiTools.create(() -> 32).bind(c);
        Caller ownerCaller = new Caller(owner, "Steve", AccessLevel.OWNER);
        var args = new com.google.gson.JsonObject();
        var listed = tools.execute(
                new dev.friendai.agent.llm.model.ToolCall("1", "find_placed", args, args.toString(), null),
                ownerCaller);
        h.assertTrue(!listed.error(), "error: " + listed.content());
        h.assertTrue(listed.content().contains("minecraft:oak_log"), listed.content());
        h.assertTrue(listed.content().contains("minecraft:chest"), listed.content());
        h.assertTrue(
                listed.content().contains(logCell.getX() + " " + logCell.getY() + " " + logCell.getZ()),
                listed.content());

        var near = new com.google.gson.JsonObject();
        near.addProperty("radius", 1);
        var none = tools.execute(
                new dev.friendai.agent.llm.model.ToolCall("2", "find_placed", near, near.toString(), null),
                ownerCaller);
        h.assertTrue(none.content().equals("no blocks you placed within 1 blocks"), none.content());

        var guest = tools.execute(
                new dev.friendai.agent.llm.model.ToolCall("3", "find_placed", args, args.toString(), null),
                new Caller(UUID.randomUUID(), "Gus", AccessLevel.GUEST));
        h.assertTrue(guest.error(), "a guest must not read the owner's placements");
        h.assertTrue(guest.content().contains("not available"), guest.content());

        String prompt = PlacedService.promptBlock(c, ownerCaller);
        h.assertTrue(prompt.contains("Blocks you placed"), prompt);
        h.assertTrue(prompt.contains("minecraft:oak_log"), prompt);
        h.assertTrue(
                PlacedService.promptBlock(c, new Caller(UUID.randomUUID(), "Gus", AccessLevel.GUEST))
                        .isEmpty(),
                "a guest prompt must not carry the ledger");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void minePlacedDoesNotTouchUnplacedSiblings(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        BlockPos placedCell = h.absolutePos(new BlockPos(4, 2, 2));
        c.hands().place(new ItemStack(Items.OAK_LOG), placedCell.below(), Direction.UP);
        h.setBlock(new BlockPos(3, 2, 4), Blocks.OAK_LOG);
        h.assertTrue(h.getLevel().getBlockState(placedCell).is(Blocks.OAK_LOG), "placed log missing");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new MineBlocksTask(BlockMatcher.parse("minecraft:oak_log").orElseThrow(), 1, 16, null, false, true),
                "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(h.getLevel().getBlockState(placedCell).isAir(), "the placed log must be mined");
            h.assertTrue(
                    h.getLevel()
                            .getBlockState(h.absolutePos(new BlockPos(3, 2, 4)))
                            .is(Blocks.OAK_LOG),
                    "the unplaced log must stay");
            h.assertTrue(
                    book(h, owner).all().isEmpty(),
                    "ledger still holds " + book(h, owner).all());
        });
    }

    @GameTest(maxTicks = 100)
    public void pickupNearPlacedFailsWithoutAPlacement(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new PickupItemsTask(8, null, true), "pickup_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status);
            h.assertTrue(result.summary.equals("you have not placed any block nearby"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void storePlacedUsesTheChestItPlaced(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        BlockPos chestCell = h.absolutePos(new BlockPos(3, 2, 3));
        c.hands().place(new ItemStack(Items.CHEST), chestCell.below(), Direction.UP);
        h.assertTrue(h.getLevel().getBlockState(chestCell).is(Blocks.CHEST), "chest missing");
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new StoreItemsTask(null, 2, null, true), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            Container chest = ContainerAccess.at(h.getLevel(), chestCell).orElseThrow();
            int stored = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(Items.COBBLESTONE)) {
                    stored += chest.getItem(slot).getCount();
                }
            }
            h.assertTrue(stored == 2, "stored " + stored + ": " + result.summary);
        });
    }

    @GameTest(maxTicks = 100)
    public void recordsBothHalvesOfADoublePlantPlacedIntoReplaceableGrass(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        h.setBlock(new BlockPos(4, 1, 2), Blocks.DIRT);
        h.setBlock(new BlockPos(4, 2, 2), Blocks.SHORT_GRASS);
        BlockPos grass = h.absolutePos(new BlockPos(4, 2, 2));
        c.hands().place(new ItemStack(Items.SUNFLOWER), grass, Direction.NORTH);
        h.assertTrue(
                h.getLevel().getBlockState(grass).is(Blocks.SUNFLOWER),
                "lower half missing: " + h.getLevel().getBlockState(grass));
        h.assertTrue(
                h.getLevel().getBlockState(grass.above()).is(Blocks.SUNFLOWER),
                "upper half missing: " + h.getLevel().getBlockState(grass.above()));
        List<PlacedBlock> entries = book(h, owner).all().stream()
                .filter(entry -> entry.pos().x() == grass.getX() && entry.pos().z() == grass.getZ())
                .toList();
        h.assertTrue(entries.size() == 2, "recorded " + entries.size() + ": " + entries);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void placedContainerIsFoundBehindManyNearerPlacements(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        int recorded = 0;
        for (int x = 3; x <= 11 && recorded < 65; x++) {
            for (int z = 2; z <= 9 && recorded < 65; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.OAK_LOG);
                BlockPos cell = h.absolutePos(new BlockPos(x, 2, z));
                PlacedService.record(c, cell, h.getLevel().getBlockState(cell), "test");
                recorded++;
            }
        }
        h.setBlock(new BlockPos(13, 2, 13), Blocks.CHEST);
        BlockPos chest = h.absolutePos(new BlockPos(13, 2, 13));
        PlacedService.record(c, chest, h.getLevel().getBlockState(chest), "test");
        var found = PlacedService.nearestContainer(c, 64);
        h.assertTrue(found.isPresent() && found.get().equals(chest), "container behind nearer placements was " + found);
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
        h.assertTrue(
                book(h, owner).all().isEmpty(),
                "a failed placement was recorded: " + book(h, owner).all());
        h.succeed();
    }
}
