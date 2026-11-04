package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.inventory.minecraft.task.CheckContainerTask;
import dev.friendai.features.inventory.minecraft.task.MoveItemsTask;
import dev.friendai.features.inventory.minecraft.task.StoreItemsTask;
import dev.friendai.features.inventory.minecraft.task.TakeItemsTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerSession;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.TagValueInput;

public class StorageTests {
    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;
        int cancelled;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled++;
        }
    }

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setEventSink(result);
        return r;
    }

    private static String coords(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static Container containerAt(GameTestHelper h, int x, int y, int z) {
        return (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, y, z)));
    }

    private static Container mergedAt(GameTestHelper h, int x, int y, int z) {
        return ContainerAccess.at(h.getLevel(), h.absolutePos(new BlockPos(x, y, z)))
                .orElseThrow();
    }

    private static StoreItemsTask store(ItemMatcher matcher, int count, GameTestHelper h, int x, int y, int z) {
        return new StoreItemsTask(matcher, count, h.absolutePos(new BlockPos(x, y, z)));
    }

    private static ItemMatcher matcher(String id) {
        return ItemMatcher.parse(id).orElseThrow();
    }

    private static void fill(Container container, net.minecraft.world.item.Item item, int slots) {
        for (int i = 0; i < slots; i++) {
            container.setItem(i, new ItemStack(item));
        }
        container.setChanged();
    }

    @GameTest(maxTicks = 400)
    public void storeEverythingInBarrel(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.BARREL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.ANDESITE, 64));
        c.inventory().addItem(new ItemStack(Items.DIORITE, 10));
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("stored 64 minecraft:andesite, 10 minecraft:diorite in minecraft:barrel at "
                            + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().isEmpty(), "the inventory must be empty");
            Container barrel = containerAt(h, 6, 2, 6);
            h.assertTrue(
                    barrel.getItem(0).is(Items.ANDESITE) && barrel.getItem(0).getCount() == 64,
                    "slot 0 was " + barrel.getItem(0));
            h.assertTrue(
                    barrel.getItem(1).is(Items.DIORITE) && barrel.getItem(1).getCount() == 10,
                    "slot 1 was " + barrel.getItem(1));
        });
    }

    @GameTest(maxTicks = 400)
    public void storeByTagInChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WOOL.orange(), 5));
        c.inventory().addItem(new ItemStack(Items.WOOL.cyan(), 3));
        c.inventory().addItem(new ItemStack(Items.GRANITE, 4));
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(matcher("#minecraft:wool"), Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("stored 5 minecraft:orange_wool, 3 minecraft:cyan_wool in minecraft:chest at "
                            + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.GRANITE) == 4, "granite must stay");
            Container chest = containerAt(h, 6, 2, 6);
            h.assertTrue(
                    chest.getItem(0).is(Items.WOOL.orange()) && chest.getItem(1).is(Items.WOOL.cyan()),
                    "wool must be stored");
        });
    }

    @GameTest(maxTicks = 600)
    public void storeFillsDoubleChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockState left = Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        h.setBlock(6, 2, 6, left);
        h.setBlock(7, 2, 6, left.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        Container first = containerAt(h, 6, 2, 6);
        fill(first, Items.DIRT, 27);
        Container merged = mergedAt(h, 6, 2, 6);
        h.assertTrue(merged.getContainerSize() == 54, "the chest must be merged, size " + merged.getContainerSize());
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (int i = 0; i < 27; i++) {
            c.inventory().addItem(new ItemStack(Items.COBBLED_DEEPSLATE, 64));
        }
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("stored 1728 minecraft:cobbled_deepslate"),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().isEmpty(), "the inventory must be empty");
            int dirtSlots = 0;
            int deepslateSlots = 0;
            for (int slot = 0; slot < 54; slot++) {
                ItemStack stack = merged.getItem(slot);
                if (stack.is(Items.DIRT) && stack.getCount() == 1) {
                    dirtSlots++;
                } else if (stack.is(Items.COBBLED_DEEPSLATE) && stack.getCount() == 64) {
                    deepslateSlots++;
                }
            }
            h.assertTrue(dirtSlots == 27, "dirt slots were " + dirtSlots);
            h.assertTrue(deepslateSlots == 27, "deepslate slots were " + deepslateSlots);
        });
    }

    @GameTest(maxTicks = 400)
    public void storePartialWhenFull(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.BARREL);
        Container barrel = containerAt(h, 6, 2, 6);
        fill(barrel, Items.DIRT, 26);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.POLISHED_ANDESITE, 64));
        c.inventory().addItem(new ItemStack(Items.POLISHED_ANDESITE, 64));
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("stored 64 minecraft:polished_andesite in minecraft:barrel at "
                            + coords(target) + "; the barrel is full"),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.POLISHED_ANDESITE) == 64, "one stack must stay");
        });
    }

    @GameTest(maxTicks = 400)
    public void storeNothingFitsFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.BARREL);
        fill(containerAt(h, 6, 2, 6), Items.DIRT, 27);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.ANDESITE, 1));
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("the barrel at " + coords(target) + " is full"),
                    "summary was " + result.summary);
        });
    }

    @GameTest
    public void storeAtFurnaceFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 1));
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(
                result.summary.equals("there is no chest, barrel or other storage at " + coords(target)),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void storeNoContainerNearby(GameTestHelper h) {
        // Far below any test structure, so parallel tests cannot put a container within 16 blocks.
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -60, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 1));
        var nearest = ContainerAccess.nearest(h.getLevel(), c.blockPosition(), 16);
        h.assertTrue(nearest.isEmpty(), "found container at " + nearest.orElse(null));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new StoreItemsTask(null, Integer.MAX_VALUE, null), "store_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(result.summary.equals("no storage container within 16 blocks"), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void storeKeepsComponents(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemStack named = new ItemStack(Items.IRON_SHOVEL);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Digger"));
        c.inventory().addItem(named);
        c.inventory().addItem(new ItemStack(Items.IRON_SHOVEL));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(matcher("minecraft:iron_shovel"), Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            Container chest = containerAt(h, 6, 2, 6);
            ItemStack first = chest.getItem(0);
            ItemStack second = chest.getItem(1);
            h.assertTrue(first.is(Items.IRON_SHOVEL) && second.is(Items.IRON_SHOVEL), "both shovels must be stored");
            h.assertTrue(
                    first.get(DataComponents.CUSTOM_NAME) != null || second.get(DataComponents.CUSTOM_NAME) != null,
                    "the name must survive");
            h.assertTrue(first.getCount() == 1 && second.getCount() == 1, "they must stay separate stacks");
        });
    }

    @GameTest(maxTicks = 400)
    public void storeLeavesMainHand(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.BARREL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.TERRACOTTA, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 6, 2, 6), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(c.getMainHandItem().is(Items.GOLDEN_PICKAXE), "the main hand must keep the pickaxe");
            h.assertTrue(c.inventory().isEmpty(), "the terracotta must be stored");
            h.assertTrue(containerAt(h, 6, 2, 6).getItem(0).is(Items.TERRACOTTA), "terracotta in the barrel");
        });
    }

    @GameTest(maxTicks = 400)
    public void storeContainerRemovedWhileWalking(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(12, 2, 3, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 1));
        BlockPos target = h.absolutePos(new BlockPos(12, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 12, 2, 3), "store_items");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            if (++ticks[0] == 3) {
                h.setBlock(12, 2, 3, Blocks.AIR);
            }
            h.assertTrue(ticks[0] >= 4, "waiting for the block change");
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("there is no chest, barrel or other storage at " + coords(target)),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void storeStopClosesContainer(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(12, 2, 3, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(12, 2, 3))),
                "first chest should be directly visible");
        c.inventory().addItem(new ItemStack(Items.DIRT, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(store(null, Integer.MAX_VALUE, h, 12, 2, 3), "store_items");
        runner.tick();
        runner.tick();
        runner.tick();
        runner.stop("test");
        h.assertTrue(c.inventory().countItem(Items.DIRT) == 5, "the inventory must be unchanged");
        h.assertTrue(result.cancelled == 1, "one cancel event, got " + result.cancelled);

        BlockPos secondChest = new BlockPos(5, 2, 5);
        h.setBlock(secondChest, Blocks.CHEST);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(secondChest)),
                "second chest should be directly visible");
        runner.start(store(null, Integer.MAX_VALUE, h, 5, 2, 5), "store_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    ChestBlockEntity.getOpenCount(h.getLevel(), h.absolutePos(new BlockPos(5, 2, 5))) == 0,
                    "the lid must be closed");
        });
    }

    /** A chest whose lid cannot open is not a chest the companion may empty. */
    @GameTest(maxTicks = 400)
    public void blockedLidCannotBeOpened(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container chest = containerAt(h, 6, 2, 6);
        chest.setItem(0, new ItemStack(Items.IRON_NUGGET, 20));
        h.setBlock(6, 3, 6, Blocks.STONE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:iron_nugget"), 3, target), "take_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task never finished");
            h.assertValueEqual(result.status, TaskStatus.FAILED, "a blocked chest was opened: " + result.summary);
            h.assertTrue(
                    result.summary.contains("locked or blocked"),
                    "the refusal must name the reason, got " + result.summary);
            h.assertValueEqual(chest.getItem(0).getCount(), 20, "items left the blocked chest");
            h.assertValueEqual(c.inventory().countItem(Items.IRON_NUGGET), 0, "the companion took items anyway");
        });
    }

    /** A locked chest refuses like it does for a player: vanilla's own menu check decides. */
    @GameTest(maxTicks = 400)
    public void lockedChestCannotBeOpened(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container chest = containerAt(h, 6, 2, 6);
        chest.setItem(0, new ItemStack(Items.IRON_NUGGET, 20));
        BlockPos absolute = h.absolutePos(new BlockPos(6, 2, 6));
        ChestBlockEntity chestEntity = (ChestBlockEntity) h.getLevel().getBlockEntity(absolute);
        var registry = h.getLevel().registryAccess();
        net.minecraft.nbt.CompoundTag tag = chestEntity.saveWithFullMetadata(registry);
        net.minecraft.nbt.CompoundTag lockTag = new net.minecraft.nbt.CompoundTag();
        lockTag.putString("items", "minecraft:stick");
        tag.put(LockCode.TAG_LOCK, lockTag);
        chestEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registry, tag));
        h.assertValueEqual(chest.getItem(0).getCount(), 20, "locking the chest must not touch its items");
        h.assertTrue(
                !chestEntity.canOpen(GameTestSupport.mockPlayerAt(h, 5, 2, 5)),
                "the loaded lock does not refuse the fake player");
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:iron_nugget"), 3, absolute), "take_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task never finished");
            h.assertValueEqual(result.status, TaskStatus.FAILED, "a locked chest was opened: " + result.summary);
            h.assertTrue(
                    result.summary.contains("locked or blocked"),
                    "the refusal must name the reason, got " + result.summary);
            h.assertValueEqual(chest.getItem(0).getCount(), 20, "items left the locked chest");
            h.assertValueEqual(c.inventory().countItem(Items.IRON_NUGGET), 0, "the companion took items anyway");
        });
    }

    @GameTest(maxTicks = 400)
    public void takeExactAmount(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container chest = containerAt(h, 6, 2, 6);
        chest.setItem(0, new ItemStack(Items.IRON_NUGGET, 20));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:iron_nugget"), 12, target), "take_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("took 12 minecraft:iron_nugget from minecraft:chest at " + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.IRON_NUGGET) == 12, "must take 12");
            h.assertTrue(
                    chest.getItem(0).getCount() == 8,
                    "8 must stay, have " + chest.getItem(0).getCount());
        });
    }

    @GameTest(maxTicks = 400)
    public void takeOnlyWhatIsThere(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container chest = containerAt(h, 6, 2, 6);
        chest.setItem(0, new ItemStack(Items.GOLD_NUGGET, 5));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:gold_nugget"), 12, target), "take_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("took only 5 of 12 minecraft:gold_nugget from minecraft:chest at "
                            + coords(target) + " (that was all it had)"),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.GOLD_NUGGET) == 5, "must take 5");
        });
    }

    @GameTest(maxTicks = 600)
    public void takeFindsChestWithItem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.CHEST);
        h.setBlock(12, 2, 6, Blocks.CHEST);
        containerAt(h, 12, 2, 6).setItem(0, new ItemStack(Items.LAPIS_LAZULI, 3));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos far = h.absolutePos(new BlockPos(12, 2, 6));
        h.assertTrue(GameTestSupport.observeVisible(h, c, far), "the chest with lapis should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:lapis_lazuli"), 3, null), "take_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("took 3 minecraft:lapis_lazuli from minecraft:chest at " + coords(far)),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.LAPIS_LAZULI) == 3, "must take 3");
        });
    }

    @GameTest(maxTicks = 400)
    public void takeWithFullInventory(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        containerAt(h, 6, 2, 6).setItem(0, new ItemStack(Items.AMETHYST_SHARD, 1));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (int i = 0; i < 27; i++) {
            c.inventory().addItem(new ItemStack(Items.STONE, 64));
        }
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:amethyst_shard"), 1, target), "take_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
        h.assertTrue(result.summary.equals("my inventory is full"), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void takeMissingItemFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        containerAt(h, 6, 2, 6).setItem(0, new ItemStack(Items.DIRT, 1));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new TakeItemsTask(matcher("minecraft:quartz"), 1, target), "take_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("the chest at " + coords(target) + " has no minecraft:quartz"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void checkListsContents(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.BARREL);
        Container barrel = containerAt(h, 6, 2, 6);
        barrel.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        barrel.setItem(3, new ItemStack(Items.IRON_INGOT, 12));
        barrel.setItem(4, new ItemStack(Items.COBBLESTONE, 20));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new CheckContainerTask(target), "check_container");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("minecraft:barrel at " + coords(target)
                            + " holds: cobblestone×84, iron_ingot×12 (3 of 27 slots used)"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void checkEmpty(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new CheckContainerTask(target), "check_container");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("minecraft:chest at " + coords(target) + " is empty"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void checkManyKinds(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container chest = containerAt(h, 6, 2, 6);
        net.minecraft.world.item.Item[] items = {
            Items.COBBLESTONE,
            Items.DIRT,
            Items.GRANITE,
            Items.DIORITE,
            Items.ANDESITE,
            Items.STONE,
            Items.OAK_LOG,
            Items.BIRCH_LOG,
            Items.SAND,
            Items.GRAVEL,
            Items.COAL,
            Items.IRON_INGOT,
            Items.GOLD_INGOT,
            Items.COPPER_INGOT,
            Items.DIAMOND,
            Items.EMERALD,
            Items.REDSTONE,
            Items.LAPIS_LAZULI,
            Items.QUARTZ,
            Items.CLAY_BALL,
            Items.BRICK,
            Items.FLINT
        };
        for (int i = 0; i < items.length; i++) {
            chest.setItem(i, new ItemStack(items[i], 1));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new CheckContainerTask(target), "check_container");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.endsWith("… and 2 more kinds (22 of 27 slots used)"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void checkStopClosesContainer(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(5, 2, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new CheckContainerTask(target), "check_container");
        boolean[] opened = {false};
        boolean[] stopped = {false};
        h.succeedWhen(() -> {
            if (!stopped[0]) {
                runner.tick();
                if (ChestBlockEntity.getOpenCount(h.getLevel(), target) > 0) {
                    opened[0] = true;
                    runner.stop("test");
                    stopped[0] = true;
                }
            }
            h.assertTrue(opened[0] && stopped[0], "waiting for the lid to open");
            h.assertTrue(
                    ChestBlockEntity.getOpenCount(h.getLevel(), target) == 0,
                    "the lid must close on stop, openers " + ChestBlockEntity.getOpenCount(h.getLevel(), target));
        });
    }

    @GameTest(maxTicks = 400)
    public void containerSessionClosesWhenWorkThrows(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 5);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 6));
        ContainerSession session =
                new ContainerSession(target, null, false, () -> "no container", ignored -> "container disappeared");
        TaskContext context = new TaskContext(c, h.getLevel());
        boolean threw = false;
        try {
            session.tick(context, (taskContext, position, container) -> {
                throw new IllegalStateException("work failed");
            });
        } catch (IllegalStateException expected) {
            threw = true;
        }
        h.assertTrue(threw, "the container work callback must run and throw");
        h.assertTrue(
                ChestBlockEntity.getOpenCount(h.getLevel(), target) == 0,
                "the lid must close after work throws, openers " + ChestBlockEntity.getOpenCount(h.getLevel(), target));
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void checkContainerRecordsContents(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container chest = containerAt(h, 6, 2, 6);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        chest.setItem(1, new ItemStack(Items.STONE, 2));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new CheckContainerTask(h.absolutePos(new BlockPos(6, 2, 6))), "check_container");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            var stored = GameTestSupport.runtime(h)
                    .storage()
                    .find(c, Set.of("minecraft:iron_ingot"), 1, c.level().getGameTime());
            h.assertTrue(stored.size() == 1 && stored.getFirst().count() == 5, "index was " + stored);
        });
    }

    @GameTest(maxTicks = 400)
    public void brokenContainerIsForgotten(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        Result check = new Result();
        Result take = new Result();
        ExecutionCoordinator checkRunner = runner(c, check);
        ExecutionCoordinator takeRunner = runner(c, take);
        checkRunner.start(new CheckContainerTask(h.absolutePos(new BlockPos(6, 2, 6))), "check_container");
        int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0) {
                checkRunner.tick();
                h.assertTrue(check.status == TaskStatus.SUCCESS, "not finished: " + check.status + " " + check.summary);
                h.setBlock(6, 2, 6, Blocks.AIR);
                takeRunner.start(
                        new TakeItemsTask(matcher("minecraft:iron_ingot"), 1, h.absolutePos(new BlockPos(6, 2, 6))),
                        "take_items");
                phase[0] = 1;
                return;
            }
            takeRunner.tick();
            h.assertTrue(take.status == TaskStatus.FAILED, "not failed: " + take.status + " " + take.summary);
            h.assertTrue(
                    GameTestSupport.runtime(h)
                            .storage()
                            .find(
                                    c,
                                    Set.of("minecraft:iron_ingot"),
                                    1,
                                    c.level().getGameTime())
                            .isEmpty(),
                    "the record must be forgotten");
        });
    }

    @GameTest(maxTicks = 400)
    public void tagQueryFindsRecordedPlanks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        containerAt(h, 6, 2, 6).setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new CheckContainerTask(h.absolutePos(new BlockPos(6, 2, 6))), "check_container");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            var ids = GameTestSupport.runtime(h).storage().itemIds("#minecraft:planks");
            var stored = GameTestSupport.runtime(h)
                    .storage()
                    .find(c, ids, 1, c.level().getGameTime());
            h.assertTrue(stored.size() == 1 && stored.getFirst().count() == 8, "index was " + stored);
        });
    }

    @GameTest(maxTicks = 600)
    public void takeWithoutCoordinatesFindsRecordedChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(12, 2, 3, Blocks.CHEST);
        containerAt(h, 12, 2, 3).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        h.setBlock(3, 2, 6, Blocks.CHEST);
        containerAt(h, 3, 2, 6).setItem(0, new ItemStack(Items.STONE, 10));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        Result check = new Result();
        Result take = new Result();
        ExecutionCoordinator checkRunner = runner(c, check);
        ExecutionCoordinator takeRunner = runner(c, take);
        checkRunner.start(new CheckContainerTask(h.absolutePos(new BlockPos(12, 2, 3))), "check_container");
        int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0) {
                checkRunner.tick();
                h.assertTrue(check.status == TaskStatus.SUCCESS, "not finished: " + check.status + " " + check.summary);
                takeRunner.start(new TakeItemsTask(matcher("minecraft:iron_ingot"), 4, null), "take_items");
                phase[0] = 1;
                return;
            }
            takeRunner.tick();
            h.assertTrue(take.status == TaskStatus.SUCCESS, "not finished: " + take.status + " " + take.summary);
            h.assertTrue(c.inventory().countItem(Items.IRON_INGOT) == 4, "iron must come from the far chest");
        });
    }

    @GameTest(maxTicks = 600)
    public void takeRevalidatesAStaleRecord(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(12, 2, 3, Blocks.CHEST);
        containerAt(h, 12, 2, 3).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        h.setBlock(3, 2, 6, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        Result check = new Result();
        Result take = new Result();
        ExecutionCoordinator checkRunner = runner(c, check);
        ExecutionCoordinator takeRunner = runner(c, take);
        checkRunner.start(new CheckContainerTask(h.absolutePos(new BlockPos(12, 2, 3))), "check_container");
        int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0) {
                checkRunner.tick();
                h.assertTrue(check.status == TaskStatus.SUCCESS, "not finished: " + check.status + " " + check.summary);
                containerAt(h, 12, 2, 3).setItem(0, ItemStack.EMPTY);
                takeRunner.start(new TakeItemsTask(matcher("minecraft:iron_ingot"), 4, null), "take_items");
                phase[0] = 1;
                return;
            }
            takeRunner.tick();
            h.assertTrue(take.status == TaskStatus.FAILED, "not failed: " + take.status + " " + take.summary);
            h.assertTrue(
                    take.summary.contains(coords(h.absolutePos(new BlockPos(12, 2, 3)))),
                    "the recorded far chest must be rechecked: " + take.summary);
            h.assertTrue(
                    GameTestSupport.runtime(h)
                            .storage()
                            .find(
                                    c,
                                    Set.of("minecraft:iron_ingot"),
                                    1,
                                    c.level().getGameTime())
                            .isEmpty(),
                    "the emptied record must be refreshed");
        });
    }

    @GameTest(maxTicks = 600)
    public void storeWithoutCoordinatesUsesRecordedFreeChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(12, 2, 3, Blocks.CHEST);
        h.setBlock(3, 2, 6, Blocks.CHEST);
        fill(containerAt(h, 3, 2, 6), Items.DIRT, 27);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        c.inventory().addItem(new ItemStack(Items.ANDESITE, 4));
        Result check = new Result();
        Result store = new Result();
        ExecutionCoordinator checkRunner = runner(c, check);
        ExecutionCoordinator storeRunner = runner(c, store);
        checkRunner.start(new CheckContainerTask(h.absolutePos(new BlockPos(12, 2, 3))), "check_container");
        int[] phase = {0};
        h.succeedWhen(() -> {
            if (phase[0] == 0) {
                checkRunner.tick();
                h.assertTrue(check.status == TaskStatus.SUCCESS, "not finished: " + check.status + " " + check.summary);
                storeRunner.start(new StoreItemsTask(null, Integer.MAX_VALUE, null), "store_items");
                phase[0] = 1;
                return;
            }
            storeRunner.tick();
            h.assertTrue(store.status == TaskStatus.SUCCESS, "not finished: " + store.status + " " + store.summary);
            h.assertTrue(
                    containerAt(h, 12, 2, 3).getItem(0).is(Items.ANDESITE),
                    "the andesite must go to the recorded empty chest, not the full near one");
            h.assertTrue(
                    containerAt(h, 3, 2, 6).getItem(0).is(Items.DIRT), "the full unrecorded chest stays untouched");
        });
    }

    @GameTest(maxTicks = 800)
    public void moveItemsMovesExactCount(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(3, 2, 3, Blocks.CHEST);
        containerAt(h, 3, 2, 3).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        h.setBlock(12, 2, 12, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        c.setOwner(java.util.UUID.randomUUID());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new MoveItemsTask(
                        matcher("minecraft:iron_ingot"),
                        7,
                        h.absolutePos(new BlockPos(3, 2, 3)),
                        h.absolutePos(new BlockPos(12, 2, 12))),
                "move_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(containerAt(h, 3, 2, 3).getItem(0).getCount() == 3, "source must keep 3");
            h.assertTrue(containerAt(h, 12, 2, 12).getItem(0).getCount() == 7, "target must hold 7");
            var runtime = GameTestSupport.runtime(h);
            h.assertTrue(
                    runtime.storage()
                                    .find(
                                            c,
                                            Set.of("minecraft:iron_ingot"),
                                            1,
                                            c.level().getGameTime())
                                    .size()
                            == 2,
                    "both records must be updated");
        });
    }

    @GameTest
    public void aSingleChestIsNotMergedIntoAnAdjacentDoubleChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockState left = Blocks.CHEST
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        h.setBlock(3, 2, 3, left);
        h.setBlock(4, 2, 3, left.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        h.setBlock(5, 2, 3, left.setValue(ChestBlock.TYPE, ChestType.SINGLE));
        BlockPos single = h.absolutePos(new BlockPos(5, 2, 3));
        BlockPos merged = h.absolutePos(new BlockPos(4, 2, 3));
        h.assertTrue(
                ContainerAccess.canonicalChest(h.getLevel(), single).equals(single),
                "a single chest must keep its own position");
        h.assertTrue(
                ContainerAccess.canonicalChest(h.getLevel(), merged).equals(h.absolutePos(new BlockPos(3, 2, 3))),
                "a merged chest must use the lower half");
        h.succeed();
    }

    @GameTest(maxTicks = 800)
    public void moveItemsReturnsLeftoversToSource(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(3, 2, 3, Blocks.CHEST);
        containerAt(h, 3, 2, 3).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        h.setBlock(12, 2, 12, Blocks.CHEST);
        fill(containerAt(h, 12, 2, 12), Items.DIRT, 27);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        c.setOwner(java.util.UUID.randomUUID());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new MoveItemsTask(
                        matcher("minecraft:iron_ingot"),
                        7,
                        h.absolutePos(new BlockPos(3, 2, 3)),
                        h.absolutePos(new BlockPos(12, 2, 12))),
                "move_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(containerAt(h, 3, 2, 3).getItem(0).getCount() == 10, "the source must get every item back");
            h.assertTrue(
                    result.summary.contains("went back to the source"),
                    "the summary must name the returned items: " + result.summary);
            h.assertTrue(result.summary.contains("7"), "the summary must name the count: " + result.summary);
        });
    }

    @GameTest(maxTicks = 800)
    public void moveItemsNamesTheMissingTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(3, 2, 3, Blocks.CHEST);
        containerAt(h, 3, 2, 3).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        c.setOwner(java.util.UUID.randomUUID());
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new MoveItemsTask(
                        matcher("minecraft:iron_ingot"),
                        7,
                        h.absolutePos(new BlockPos(3, 2, 3)),
                        h.absolutePos(new BlockPos(12, 2, 12))),
                "move_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("no storage container"),
                    "the refusal must name the missing target: " + result.summary);
        });
    }
}
