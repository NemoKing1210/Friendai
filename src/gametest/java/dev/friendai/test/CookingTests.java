package dev.friendai.test;

import dev.friendai.debug.DebugAction;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.cooking.minecraft.FuelPolicy;
import dev.friendai.features.cooking.minecraft.FurnaceJob;
import dev.friendai.features.cooking.minecraft.FurnaceSlots;
import dev.friendai.features.cooking.minecraft.task.CollectSmeltedTask;
import dev.friendai.features.cooking.minecraft.task.CollectStationTask;
import dev.friendai.features.cooking.minecraft.task.CookFoodTask;
import dev.friendai.features.cooking.minecraft.task.CraftingTaskFactory;
import dev.friendai.features.cooking.minecraft.task.SmeltTask;
import dev.friendai.features.crafting.Recipe;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.StationPolicy;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CookingTests {
    /** Records the notices and task outcomes a companion's runner reports. */
    private static final class Result implements dev.friendai.server.execution.TaskEventSink {
        final List<String> notices = new java.util.ArrayList<>();
        dev.friendai.task.TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, dev.friendai.task.TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @GameTest
    public void smeltDebugTargetShowsActiveFurnace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        c.inventory().addItem(new ItemStack(Items.FURNACE, 1));
        SmeltTask task = new SmeltTask(
                ItemMatcher.parse("minecraft:raw_gold").orElseThrow(),
                1,
                Station.FURNACE,
                null,
                false,
                StationPolicy.AUTO,
                20);
        TaskContext context = new TaskContext(c, h.getLevel());
        task.start(context);
        task.tick(context);
        h.assertTrue(
                task.debugTargets().stream().anyMatch(target -> target.action() == DebugAction.PLACE),
                "the selected furnace placement spot should be shown while the companion approaches");
        h.succeed();
    }

    @GameTest
    public void stationDebugTargetChangesFromCollectToBreak(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos station = h.absolutePos(new BlockPos(4, 2, 4));
        h.setBlock(new BlockPos(4, 2, 4), Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 4);
        CollectStationTask task = new CollectStationTask(Station.FURNACE, station);
        TaskContext context = new TaskContext(c, h.getLevel());
        task.start(context);
        h.assertTrue(
                task.debugTargets().stream().anyMatch(target -> target.action() == DebugAction.COLLECT),
                "the station should begin as a collect target");
        task.tick(context);
        h.assertTrue(
                task.debugTargets().stream().anyMatch(target -> target.action() == DebugAction.BREAK),
                "taking the station back should change its target action to break");
        h.succeed();
    }

    private static net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnaceAt(
            GameTestHelper h, int x, int y, int z) {
        h.setBlock(x, y, z, Blocks.FURNACE);
        return (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                h.getLevel().getBlockEntity(pos(h, x, y, z));
    }

    @GameTest
    public void busyFurnaceIsNotFree(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        h.assertFalse(
                FurnaceSlots.isFreeFor(furnace, Items.RAW_COPPER, Items.COPPER_INGOT),
                "a furnace with someone's raw iron is busy");

        furnace.setItem(0, ItemStack.EMPTY);
        furnace.setItem(2, new ItemStack(Items.COPPER_INGOT));
        h.assertTrue(
                FurnaceSlots.isFreeFor(furnace, Items.RAW_COPPER, Items.COPPER_INGOT),
                "our own output in the result slot is fine");

        furnace.setItem(2, new ItemStack(Items.STONE));
        h.assertFalse(
                FurnaceSlots.isFreeFor(furnace, Items.RAW_COPPER, Items.COPPER_INGOT),
                "someone else's output makes the furnace busy");
        h.succeed();
    }

    @GameTest
    public void resultSlotAlmostFull(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        furnace.setItem(2, new ItemStack(Items.COPPER_INGOT, 62));
        int loaded = FurnaceSlots.load(furnace, new ItemStack(Items.RAW_COPPER, 5), new ItemStack(Items.COAL));
        h.assertValueEqual(loaded, 2, "only the room left in the result slot");
        h.assertValueEqual(furnace.getItem(0).getCount(), 2, "input loaded");
        h.assertTrue(furnace.getItem(1).is(Items.COAL), "fuel loaded");
        h.succeed();
    }

    @GameTest
    public void queuedInputCountsAgainstResultRoom(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        furnace.setItem(2, new ItemStack(Items.COPPER_INGOT, 60));
        furnace.setItem(0, new ItemStack(Items.RAW_COPPER, 3));
        int loaded = FurnaceSlots.load(furnace, new ItemStack(Items.RAW_COPPER, 5), new ItemStack(Items.COAL));
        h.assertValueEqual(loaded, 1, "only the one free result slot");
        h.assertValueEqual(furnace.getItem(0).getCount(), 4, "queued input plus the one loaded");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void watcherReportsFinished(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        BlockPos pos = pos(h, 2, 2, 2);
        furnace.setItem(0, new ItemStack(Items.RAW_GOLD));
        furnace.setItem(1, new ItemStack(Items.COAL));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        c.furnaceJobs()
                .add(new FurnaceJob(pos, Station.FURNACE, "minecraft:raw_gold", "minecraft:gold_ingot", 1, false));
        String expected =
                "the furnace at " + coords(pos) + " finished: 1 minecraft:gold_ingot ready (use collect_smelted)";
        h.succeedWhen(() -> h.assertTrue(result.notices.contains(expected), "notices: " + result.notices));
    }

    @GameTest(maxTicks = 600)
    public void watcherReportsOutOfFuel(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        BlockPos pos = pos(h, 2, 2, 2);
        furnace.setItem(0, new ItemStack(Items.KELP, 2));
        furnace.setItem(1, new ItemStack(Items.OAK_PLANKS));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        c.furnaceJobs().add(new FurnaceJob(pos, Station.FURNACE, "minecraft:kelp", "minecraft:dried_kelp", 2, false));
        String expected = "the furnace at " + coords(pos) + " stopped: out of fuel, 1 minecraft:kelp left";
        h.succeedWhen(() -> h.assertTrue(result.notices.contains(expected), "notices: " + result.notices));
    }

    @GameTest(maxTicks = 100)
    public void watcherReportsGoneFurnace(GameTestHelper h) {
        furnaceAt(h, 2, 2, 2);
        BlockPos pos = pos(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        c.furnaceJobs()
                .add(new FurnaceJob(pos, Station.FURNACE, "minecraft:raw_gold", "minecraft:gold_ingot", 1, false));
        h.setBlock(2, 2, 2, Blocks.AIR);
        String expected = "the furnace at " + coords(pos) + " is gone; the job for minecraft:gold_ingot was dropped";
        h.succeedWhen(() -> {
            h.assertTrue(result.notices.contains(expected), "notices: " + result.notices);
            h.assertTrue(
                    c.furnaceJobs().all().isEmpty(),
                    "the job should be dropped: " + c.furnaceJobs().all());
        });
    }

    private static final int GIVEN_DECK = 267;
    private static final int TAKEOVER_DECK = 267;
    private static final int NOPICK_DECK = 284;
    private static final int LAVA_DECK = 301;

    private static ExecutionCoordinator collect(CompanionEntity c, Result result, BlockPos target) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        runner.start(new CollectSmeltedTask(target), "collect_smelted");
        return runner;
    }

    private static boolean resultReady(GameTestHelper h, BlockPos pos, net.minecraft.world.item.Item item) {
        return h.getLevel().getBlockEntity(pos)
                        instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace
                && furnace.getItem(2).is(item);
    }

    @GameTest(maxTicks = 900)
    public void loadThenCollect(GameTestHelper h) {
        deck(h, 16, COLLECT_DECK);
        h.setBlock(4, COLLECT_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, COLLECT_DECK + 1, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, COLLECT_DECK + 1, 4);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 4, COLLECT_DECK + 1, 2)),
                "first furnace should be visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 8, COLLECT_DECK + 1, 2)),
                "second furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 2));
        c.inventory().addItem(new ItemStack(Items.COAL, 2));
        Result loaded = new Result();
        ExecutionCoordinator smelt = smelt(c, loaded, "minecraft:raw_gold", 2, Station.FURNACE, null, false, 20);
        Result collected = new Result();
        ExecutionCoordinator[] collector = {null};
        BlockPos first = pos(h, 4, COLLECT_DECK + 1, 2);
        BlockPos second = pos(h, 8, COLLECT_DECK + 1, 2);
        h.succeedWhen(() -> {
            if (collector[0] == null
                    && loaded.status == TaskStatus.SUCCESS
                    && resultReady(h, first, Items.GOLD_INGOT)
                    && resultReady(h, second, Items.GOLD_INGOT)) {
                collector[0] = collect(c, collected, null);
            }
            if (collector[0] != null) {
                collector[0].tick();
                h.assertTrue(
                        collected.status == TaskStatus.SUCCESS,
                        "collect not finished: " + collected.status + " " + collected.summary);
                h.assertValueEqual(collected.summary, "collected 2 minecraft:gold_ingot from 2 furnaces", "summary");
                h.assertTrue(
                        c.furnaceJobs().all().isEmpty(),
                        "jobs left: " + c.furnaceJobs().all());
            } else {
                smelt.tick();
                h.assertTrue(loaded.status == TaskStatus.SUCCESS, "load not finished: " + loaded.summary);
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void collectFromGivenFurnace(GameTestHelper h) {
        deck(h, 16, GIVEN_DECK);
        var furnace =
                (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) furnaceAt(h, 4, GIVEN_DECK + 1, 2);
        BlockPos pos = pos(h, 4, GIVEN_DECK + 1, 2);
        furnace.setItem(2, new ItemStack(Items.COOKED_SALMON, 3));
        furnace.setItem(1, new ItemStack(Items.COAL));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, GIVEN_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "furnace should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = collect(c, result, pos);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "collected 3 minecraft:cooked_salmon from 1 furnace", "summary");
            h.assertTrue(furnace.getItem(1).is(Items.COAL), "the fuel slot must stay untouched");
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_SALMON), 3, "cooked salmon");
        });
    }

    @GameTest
    public void collectNothingFails(GameTestHelper h) {
        deck(h, 16, GIVEN_DECK);
        furnaceAt(h, 4, GIVEN_DECK + 1, 2);
        BlockPos pos = pos(h, 4, GIVEN_DECK + 1, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, GIVEN_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "empty furnace should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = collect(c, result, pos);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "no furnace with finished items nearby", "summary");
        });
    }

    @GameTest(maxTicks = 1600)
    public void placedFurnaceKeptUntilCollected(GameTestHelper h) {
        deck(h, 16, TAKEOVER_DECK);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, TAKEOVER_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.FURNACE, 1));
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result loaded = new Result();
        ExecutionCoordinator smelt = smelt(c, loaded, "minecraft:raw_copper", 1, Station.FURNACE, null, false, 20);
        Result collected = new Result();
        ExecutionCoordinator[] collector = {null};
        h.succeedWhen(() -> {
            if (loaded.status == TaskStatus.SUCCESS && collector[0] == null) {
                h.assertTrue(!c.furnaceJobs().all().isEmpty(), "a job should exist");
                BlockPos furnacePos = c.furnaceJobs().all().get(0).pos();
                h.assertTrue(resultReady(h, furnacePos, Items.COPPER_INGOT), "waiting for the copper");
                collector[0] = collect(c, collected, null);
            }
            if (collector[0] != null) {
                collector[0].tick();
                h.assertTrue(
                        collected.status == TaskStatus.SUCCESS,
                        "collect not finished: " + collected.status + " " + collected.summary);
                h.assertValueEqual(c.inventory().countItem(Items.FURNACE), 1, "furnace item back");
                h.assertFalse(hasBlock(h, Blocks.FURNACE, 16, TAKEOVER_DECK), "the placed furnace should be gone");
            } else {
                smelt.tick();
                h.assertTrue(loaded.status == TaskStatus.SUCCESS, "load not finished: " + loaded.summary);
                h.assertTrue(hasBlock(h, Blocks.FURNACE, 16, TAKEOVER_DECK), "the placed furnace stays");
            }
        });
    }

    @GameTest(maxTicks = 1600)
    public void noPickaxeLeavesFurnace(GameTestHelper h) {
        deck(h, 16, NOPICK_DECK);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, NOPICK_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.FURNACE, 1));
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "minecraft:raw_copper", 1, Station.FURNACE, null, false, 60 * 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("I have no pickaxe to pick it up"), "summary was " + result.summary);
            h.assertTrue(hasBlock(h, Blocks.FURNACE, 16, NOPICK_DECK), "the furnace must stay");
            h.assertTrue(c.furnaceJobs().all().isEmpty(), "a waited job leaves no furnace job");
        });
    }

    @GameTest(maxTicks = 1600)
    public void waitModeTakesBackPlacedFurnace(GameTestHelper h) {
        deck(h, 16, NOPICK_DECK);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, NOPICK_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.FURNACE, 1));
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "minecraft:raw_copper", 1, Station.FURNACE, null, false, 60 * 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("and took it back"), "summary was " + result.summary);
            h.assertFalse(hasBlock(h, Blocks.FURNACE, 16, NOPICK_DECK), "the furnace should be taken back");
            h.assertValueEqual(c.inventory().countItem(Items.FURNACE), 1, "furnace item back");
            h.assertTrue(
                    result.notices.stream().noneMatch(text -> text.contains("is gone")),
                    "no gone notice: " + result.notices);
        });
    }

    @GameTest(maxTicks = 1600)
    public void lavaBucketReturnsEmptyBucket(GameTestHelper h) {
        deck(h, 16, LAVA_DECK);
        h.setBlock(4, LAVA_DECK + 1, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, LAVA_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 4, LAVA_DECK + 1, 2)), "furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.LAVA_BUCKET, 1));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "minecraft:raw_copper", 1, Station.FURNACE, "minecraft:lava_bucket", false, 60 * 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.BUCKET), 1, "the empty bucket");
            h.assertValueEqual(c.inventory().countItem(Items.LAVA_BUCKET), 0, "lava buckets left");
        });
    }

    @GameTest(maxTicks = 1200)
    public void cookFoodCooksAllRaw(GameTestHelper h) {
        deck(h, 16, LOAD_DECK);
        h.setBlock(8, LOAD_DECK + 1, 2, Blocks.SMOKER);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, LOAD_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 8, LOAD_DECK + 1, 2)), "smoker should be visible");
        c.inventory().addItem(new ItemStack(Items.CHICKEN, 1));
        c.inventory().addItem(new ItemStack(Items.RABBIT, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        runner.start(new CookFoodTask(256, false, StationPolicy.AUTO, 60 * 20), "cook_food");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_CHICKEN), 1, "cooked chicken");
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_RABBIT), 1, "cooked rabbit");
        });
    }

    @GameTest
    public void cookFoodWithoutRawFood(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        runner.start(new CookFoodTask(256, false, StationPolicy.AUTO, 60 * 20), "cook_food");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(result.summary, "I have no raw food", "summary");
        h.succeed();
    }

    private static boolean hasItem(CompanionEntity c, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
            if (c.inventory().getItem(slot).is(item)) {
                return true;
            }
        }
        return c.getMainHandItem().is(item);
    }

    @GameTest(maxTicks = 600)
    public void waitsAndCollects(GameTestHelper h) {
        deck(h, 16, WAIT_DECK);
        h.setBlock(4, WAIT_DECK + 1, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, WAIT_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 4, WAIT_DECK + 1, 2)), "furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "minecraft:raw_copper", 1, Station.FURNACE, null, false, 60 * 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith(
                            "smelted 1 minecraft:raw_copper into 1 minecraft:copper_ingot in 1 furnace"),
                    "summary was " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.COPPER_INGOT), 1, "copper ingots");
            h.assertTrue(c.furnaceJobs().all().isEmpty(), "a waited job leaves no furnace job");
        });
    }

    @GameTest(maxTicks = 1200)
    public void campfireCooks(GameTestHelper h) {
        deck(h, 16, WAIT_DECK);
        h.setBlock(4, WAIT_DECK + 1, 2, Blocks.CAMPFIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, WAIT_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 4, WAIT_DECK + 1, 2)), "campfire should be visible");
        c.inventory().addItem(new ItemStack(Items.MUTTON, 2));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:mutton", 2, Station.CAMPFIRE, null, false, 60 * 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_MUTTON), 2, "cooked mutton");
            h.assertTrue(
                    c.furnaceJobs().all().isEmpty(),
                    "a campfire needs no furnace job: " + c.furnaceJobs().all());
            h.assertTrue(
                    result.notices.stream().noneMatch(text -> text.contains("is gone")),
                    "no gone notice: " + result.notices);
        });
    }

    @GameTest(maxTicks = 1200)
    public void outOfFuelWhileWaiting(GameTestHelper h) {
        deck(h, 16, WAIT_DECK);
        h.setBlock(4, WAIT_DECK + 1, 2, Blocks.FURNACE);
        BlockPos pos = pos(h, 4, WAIT_DECK + 1, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, WAIT_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.KELP, 2));
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:kelp", 2, Station.FURNACE, null, false, 60 * 20);
        String expected = "ran out of fuel; 1 minecraft:dried_kelp collected, "
                + "1 minecraft:kelp still in the furnace at " + coords(pos);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, expected, "summary");
            h.assertValueEqual(c.inventory().countItem(Items.DRIED_KELP), 1, "dried kelp");
        });
    }

    @GameTest(maxTicks = 800)
    public void furnaceBrokenWhileWaiting(GameTestHelper h) {
        deck(h, 16, BROKEN_DECK);
        h.setBlock(4, BROKEN_DECK + 1, 2, Blocks.FURNACE);
        BlockPos pos = pos(h, 4, BROKEN_DECK + 1, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, BROKEN_DECK + 1, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 2));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "minecraft:raw_copper", 2, Station.FURNACE, null, false, 60 * 20);
        boolean[] broken = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!broken[0]
                    && h.getLevel().getBlockEntity(pos)
                            instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace
                    && !furnace.getItem(0).isEmpty()) {
                h.setBlock(4, BROKEN_DECK + 1, 2, Blocks.AIR);
                broken[0] = true;
            }
            h.assertTrue(broken[0], "waiting for the furnace to be loaded");
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(
                    result.summary,
                    "the furnace at " + coords(pos) + " is gone; 0 minecraft:copper_ingot collected so far",
                    "summary");
        });
    }

    @GameTest(maxTicks = 1200)
    public void tagInputsSmeltedTypeByType(GameTestHelper h) {
        deck(h, 16, TAG_DECK);
        h.setBlock(4, TAG_DECK + 1, 2, Blocks.BLAST_FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, TAG_DECK + 1, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 4, TAG_DECK + 1, 2)), "blast furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.IRON_ORE, 1));
        c.inventory().addItem(new ItemStack(Items.DEEPSLATE_IRON_ORE, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "#minecraft:iron_ores", 2, Station.BLAST_FURNACE, null, false, 60 * 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.IRON_INGOT), 2, "iron ingots");
            h.assertTrue(
                    result.summary.startsWith("smelted 2 #minecraft:iron_ores into 2 minecraft:iron_ingot"),
                    "summary was " + result.summary);
        });
    }

    // Task 5: load-and-leave scenes live on their own decks, 24 blocks apart, so no test sees another's stations.
    private static final int LOAD_DECK = 4;
    private static final int BLAST_DECK = 4;
    private static final int SMOKER_DECK = -30;
    private static final int BUSY_DECK = -13;
    private static final int NOFUEL_DECK = 233;
    private static final int CRAFT_DECK = -30;
    private static final int CAMPFIRE_DECK = -13;
    private static final int WAIT_DECK = -47;
    private static final int TAG_DECK = -64;
    private static final int BROKEN_DECK = 233;
    private static final int COLLECT_DECK = 250;

    private static void deck(GameTestHelper h, int size, int y) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, y, z, Blocks.STONE);
                for (int above = y + 1; above <= y + 5; above++) {
                    h.setBlock(x, above, z, Blocks.AIR);
                }
            }
        }
    }

    private static boolean hasBlock(GameTestHelper h, net.minecraft.world.level.block.Block block, int size, int y) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int scan = y; scan <= y + 3; scan++) {
                    if (h.getLevel()
                            .getBlockState(h.absolutePos(new BlockPos(x, scan, z)))
                            .is(block)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ExecutionCoordinator smelt(
            CompanionEntity c,
            Result result,
            String item,
            int count,
            Station forced,
            String fuel,
            boolean forceWait,
            int waitLimitTicks) {
        return smelt(c, result, item, count, forced, fuel, forceWait, waitLimitTicks, CraftingTaskFactory.UNAVAILABLE);
    }

    private static ExecutionCoordinator smelt(
            CompanionEntity c,
            Result result,
            String item,
            int count,
            Station forced,
            String fuel,
            boolean forceWait,
            int waitLimitTicks,
            CraftingTaskFactory craftingTaskFactory) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        ItemMatcher input = ItemMatcher.parse(item).orElseThrow();
        ItemMatcher fuelMatcher = fuel == null ? null : ItemMatcher.parse(fuel).orElseThrow();
        runner.start(
                new SmeltTask(
                        input,
                        count,
                        forced,
                        fuelMatcher,
                        forceWait,
                        StationPolicy.AUTO,
                        waitLimitTicks,
                        craftingTaskFactory),
                "smelt_items");
        return runner;
    }

    @GameTest(maxTicks = 400)
    public void loadsTwoFurnaces(GameTestHelper h) {
        deck(h, 16, LOAD_DECK);
        h.setBlock(4, LOAD_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, LOAD_DECK + 1, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, LOAD_DECK + 1, 4);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 4, LOAD_DECK + 1, 2)), "first furnace should be visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 8, LOAD_DECK + 1, 2)), "second furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 2));
        c.inventory().addItem(new ItemStack(Items.COAL, 2));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:raw_gold", 2, Station.FURNACE, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("loaded 2 furnaces with 2 minecraft:raw_gold, ready in about 10 s"),
                    "summary was " + result.summary);
            int loaded = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    var be = h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, LOAD_DECK + 1, z)));
                    if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace
                            && furnace.getItem(0).is(Items.RAW_GOLD)) {
                        loaded += furnace.getItem(0).getCount();
                    }
                }
            }
            h.assertValueEqual(loaded, 2, "raw gold in the furnaces");
            h.assertValueEqual(c.furnaceJobs().all().size(), 2, "jobs");
        });
    }

    @GameTest(maxTicks = 400)
    public void blastFurnacePreferredForOre(GameTestHelper h) {
        deck(h, 16, BLAST_DECK);
        h.setBlock(4, BLAST_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, BLAST_DECK + 1, 2, Blocks.BLAST_FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, BLAST_DECK + 1, 4);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 4, BLAST_DECK + 1, 2)), "furnace should be visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 8, BLAST_DECK + 1, 2)), "blast furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:raw_gold", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("blast furnace"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void smokerPreferredForFood(GameTestHelper h) {
        deck(h, 16, SMOKER_DECK);
        h.setBlock(4, SMOKER_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, SMOKER_DECK + 1, 2, Blocks.SMOKER);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, SMOKER_DECK + 1, 4);
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 4, SMOKER_DECK + 1, 2)), "furnace should be visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos(h, 8, SMOKER_DECK + 1, 2)), "smoker should be visible");
        c.inventory().addItem(new ItemStack(Items.COD, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:cod", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("smoker"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void busyFurnaceSkipped(GameTestHelper h) {
        deck(h, 16, BUSY_DECK);
        h.setBlock(4, BUSY_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, BUSY_DECK + 1, 2, Blocks.FURNACE);
        var busy = (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                h.getLevel().getBlockEntity(pos(h, 4, BUSY_DECK + 1, 2));
        busy.setItem(0, new ItemStack(Items.RAW_IRON));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, BUSY_DECK + 1, 4);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 4, BUSY_DECK + 1, 2)), "busy furnace should be visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 8, BUSY_DECK + 1, 2)), "free furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:raw_copper", 1, Station.FURNACE, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(busy.getItem(0).is(Items.RAW_IRON), "the busy furnace must be untouched");
            h.assertValueEqual(c.furnaceJobs().all().size(), 1, "one job");
        });
    }

    @GameTest
    public void nothingToSmeltFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:raw_copper", 1, Station.FURNACE, null, false, 20);
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(result.summary, "I don't have minecraft:raw_copper", "summary");
        h.succeed();
    }

    @GameTest
    public void notSmeltableFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:dirt", 1, null, null, false, 20);
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(result.summary, "minecraft:dirt can't be smelted or cooked", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void noAllowedFuelFails(GameTestHelper h) {
        deck(h, 16, NOFUEL_DECK);
        h.setBlock(4, NOFUEL_DECK + 1, 2, Blocks.BLAST_FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, NOFUEL_DECK + 1, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, pos(h, 4, NOFUEL_DECK + 1, 2)), "blast furnace should be visible");
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.OAK_BOAT));
        Result result = new Result();
        ExecutionCoordinator runner =
                smelt(c, result, "minecraft:raw_copper", 1, Station.BLAST_FURNACE, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "I have no fuel I'm allowed to burn", "summary");
        });
    }

    @GameTest(maxTicks = 1600)
    public void craftsFurnaceWhenNoneAround(GameTestHelper h) {
        deck(h, 16, CRAFT_DECK);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, CRAFT_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 3));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(
                c,
                result,
                "minecraft:raw_copper",
                1,
                Station.FURNACE,
                null,
                false,
                20,
                new MinecraftObtainSubtaskFactory());
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(hasBlock(h, Blocks.FURNACE, 16, CRAFT_DECK), "a furnace should have been placed");
            boolean loaded = false;
            for (int x = 0; x < 16 && !loaded; x++) {
                for (int z = 0; z < 16 && !loaded; z++) {
                    var be = h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, CRAFT_DECK + 1, z)));
                    loaded = be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace
                            && furnace.getItem(0).is(Items.RAW_COPPER);
                }
            }
            h.assertTrue(loaded, "the raw copper should be in the placed furnace");
        });
    }

    @GameTest(maxTicks = 400)
    public void longCampfireJobRefused(GameTestHelper h) {
        deck(h, 16, CAMPFIRE_DECK);
        h.setBlock(6, CAMPFIRE_DECK + 1, 2, Blocks.CAMPFIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, CAMPFIRE_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.PORKCHOP, 5));
        Result result = new Result();
        ExecutionCoordinator runner = smelt(c, result, "minecraft:porkchop", 5, Station.CAMPFIRE, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(
                    result.summary,
                    "that would take too long on a campfire; ask me to wait or give me a furnace",
                    "summary");
        });
    }

    private static List<Recipe> cookingFrom(GameTestHelper h, String item) {
        return RecipeIndex.of(h.getLevel()).cookingFrom(item);
    }

    private static Recipe station(GameTestHelper h, String input, Station station) {
        return cookingFrom(h, input).stream()
                .filter(recipe -> recipe.station() == station)
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("no " + station + " recipe for " + input + ": " + cookingFrom(h, input)));
    }

    private static BlockPos pos(GameTestHelper h, int x, int y, int z) {
        return h.absolutePos(new BlockPos(x, y, z));
    }

    @GameTest
    public void indexHasCookingRecipes(GameTestHelper h) {
        Recipe furnace = station(h, "minecraft:raw_iron", Station.FURNACE);
        h.assertValueEqual(furnace.output(), "minecraft:iron_ingot", "furnace output");
        h.assertValueEqual(furnace.cookTicks(), 200, "furnace cook time");
        h.assertValueEqual(furnace.ingredients().size(), 1, "furnace ingredient count");

        Recipe blasting = station(h, "minecraft:raw_iron", Station.BLAST_FURNACE);
        h.assertValueEqual(blasting.output(), "minecraft:iron_ingot", "blast furnace output");
        h.assertValueEqual(blasting.cookTicks(), 200, "blast furnace cook time");

        station(h, "minecraft:beef", Station.SMOKER);
        station(h, "minecraft:beef", Station.CAMPFIRE);
        h.succeed();
    }

    @GameTest
    public void burnTicksFromComponent(GameTestHelper h) {
        BlockPos furnace = pos(h, 2, 2, 2);
        h.assertValueEqual(FuelPolicy.burnTicks(h.getLevel(), furnace, new ItemStack(Items.COAL)), 1600, "coal");
        h.assertValueEqual(FuelPolicy.burnTicks(h.getLevel(), furnace, new ItemStack(Items.STICK)), 100, "stick");
        h.assertValueEqual(
                FuelPolicy.burnTicks(h.getLevel(), furnace, new ItemStack(Items.OAK_PLANKS)), 300, "oak planks");
        h.assertFalse(FuelPolicy.isFuel(new ItemStack(Items.DIRT)), "dirt is not a fuel");
        h.succeed();
    }

    @GameTest
    public void fuelPickPrefersCheapOrder(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
        c.inventory().addItem(new ItemStack(Items.COAL));
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        BlockPos furnace = pos(h, 4, 2, 4);

        FuelPolicy.FuelPick coal = FuelPolicy.pick(c, h.getLevel(), furnace, 400, null);
        h.assertValueEqual(coal.stacks().size(), 1, "one pick");
        h.assertTrue(coal.stacks().get(0).is(Items.COAL), "coal is preferred: " + coal.stacks());
        h.assertValueEqual(coal.stacks().get(0).getCount(), 1, "one coal covers 400 ticks");
        h.assertTrue(coal.ticksCovered() >= 400, "ticks covered: " + coal.ticksCovered());

        c.inventory().clearContent();
        c.inventory().addItem(new ItemStack(Items.STICK, 4));
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        FuelPolicy.FuelPick sticks = FuelPolicy.pick(c, h.getLevel(), furnace, 400, null);
        h.assertValueEqual(sticks.stacks().size(), 1, "one pick");
        h.assertTrue(sticks.stacks().get(0).is(Items.STICK), "sticks: " + sticks.stacks());
        h.assertValueEqual(sticks.stacks().get(0).getCount(), 4, "four sticks");
        h.assertTrue(
                sticks.stacks().stream().noneMatch(stack -> stack.is(Items.IRON_PICKAXE)),
                "a tool is never picked: " + sticks.stacks());
        h.succeed();
    }

    @GameTest
    public void fuelPickUsesOneType(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 10));
        BlockPos furnace = pos(h, 4, 2, 4);

        FuelPolicy.FuelPick pick = FuelPolicy.pick(c, h.getLevel(), furnace, 12_800, null);
        h.assertValueEqual(pick.stacks().size(), 1, "a furnace burns one fuel type at a time: " + pick.stacks());
        h.assertTrue(pick.stacks().get(0).is(Items.COAL), "coal wins the priority: " + pick.stacks());
        h.succeed();
    }

    @GameTest
    public void fuelPickExplicit(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_BOAT));
        BlockPos furnace = pos(h, 4, 2, 4);

        FuelPolicy.FuelPick none = FuelPolicy.pick(c, h.getLevel(), furnace, 400, null);
        h.assertTrue(none.stacks().isEmpty(), "a boat is not a cheap fuel: " + none.stacks());

        ItemMatcher boat = ItemMatcher.parse("minecraft:oak_boat").orElseThrow();
        FuelPolicy.FuelPick explicit = FuelPolicy.pick(c, h.getLevel(), furnace, 400, boat);
        h.assertValueEqual(explicit.stacks().size(), 1, "one pick");
        h.assertTrue(explicit.stacks().get(0).is(Items.OAK_BOAT), "the explicit boat: " + explicit.stacks());
        h.succeed();
    }

    @GameTest
    public void campfireStationMatchesLitCampfireOnly(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 2, Blocks.CAMPFIRE);
        BlockPos campfire = pos(h, 6, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, companion, campfire), "lit campfire should be visible");
        h.assertValueEqual(Stations.nearby(companion, Station.CAMPFIRE), Optional.of(campfire), "the lit campfire");
        h.setBlock(
                6,
                2,
                2,
                Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
        // Batches share one structure position, so a campfire another test left behind can sit within the search
        // radius: assert that this campfire is not found, not that no campfire is found anywhere.
        h.assertTrue(
                Stations.nearby(companion, Station.CAMPFIRE)
                        .filter(campfire::equals)
                        .isEmpty(),
                "an unlit campfire is not usable");
        h.succeed();
    }
}
