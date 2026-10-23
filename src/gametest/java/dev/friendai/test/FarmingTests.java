package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskFactory;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.BonemealTask;
import dev.friendai.task.builtin.FarmTask;
import dev.friendai.task.builtin.TendFarmTask;
import dev.friendai.task.farming.FarmPass;
import dev.friendai.task.farming.FarmReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public class FarmingTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        int cancelled;
        final List<String> notices = new ArrayList<>();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled++;
        }

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }
    }

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        return runner;
    }

    /** A 3x3 plot of farmland with water in the middle; returns the eight ring cells. */
    private static BlockPos[] plot(GameTestHelper h) {
        BlockPos[] ring = new BlockPos[8];
        int i = 0;
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                if (x == 5 && z == 5) {
                    h.setBlock(x, 1, z, Blocks.WATER);
                } else {
                    h.setBlock(x, 1, z, Blocks.FARMLAND);
                    ring[i++] = new BlockPos(x, 1, z);
                }
            }
        }
        return ring;
    }

    private static FarmPass pass(GameTestHelper h, int x, int y, int z, boolean till) {
        return new FarmPass(h.absolutePos(new BlockPos(x, y, z)), 8, null, till);
    }

    private static int age(GameTestHelper h, int x, int y, int z) {
        BlockState state = h.getBlockState(new BlockPos(x, y, z));
        return state.hasProperty(net.minecraft.world.level.block.CropBlock.AGE)
                ? state.getValue(net.minecraft.world.level.block.CropBlock.AGE)
                : -1;
    }

    @GameTest(maxTicks = 1200)
    public void harvestsMatureAndReplants(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] ring = plot(h);
        int[] mature = {0, 1, 2, 3};
        int[] immature = {4, 5, 6, 7};
        for (int index : mature) {
            h.setBlock(ring[index].getX(), 2, ring[index].getZ(), Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
        }
        for (int index : immature) {
            h.setBlock(ring[index].getX(), 2, ring[index].getZ(), Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 3));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        c.inventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 4));
        FarmPass pass = pass(h, 5, 2, 5, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            FarmReport report = pass.report();
            h.assertValueEqual(report.harvested().get("minecraft:wheat"), 4, "harvested wheat " + report.harvested());
            h.assertValueEqual(report.replanted(), 4, "replanted");
            for (int index : mature) {
                h.assertValueEqual(age(h, ring[index].getX(), 2, ring[index].getZ()), 0, "mature plot replanted");
            }
            for (int index : immature) {
                int age = age(h, ring[index].getX(), 2, ring[index].getZ());
                h.assertTrue(age >= 3, "immature plot untouched, age " + age);
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void replantsFromDrops(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] ring = plot(h);
        for (int index = 0; index < 4; index++) {
            h.setBlock(ring[index].getX(), 2, ring[index].getZ(), Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        FarmPass pass = pass(h, 5, 2, 5, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertValueEqual(pass.report().replanted(), 4, "replanted from the harvest drops");
            for (int index = 0; index < 4; index++) {
                h.assertValueEqual(age(h, ring[index].getX(), 2, ring[index].getZ()), 0, "plot replanted");
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void farmlandGoneSkipsReplant(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] ring = plot(h);
        for (int index = 0; index < 4; index++) {
            h.setBlock(ring[index].getX(), 2, ring[index].getZ(), Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        FarmPass pass = pass(h, 5, 2, 5, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        pass.tick(ctx);
        BlockPos gone = ring[3];
        h.setBlock(gone.getX(), 1, gone.getZ(), Blocks.DIRT);
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertValueEqual(pass.report().replanted(), 3, "one plot lost its farmland");
            h.assertTrue(h.getBlockState(new BlockPos(gone.getX(), 1, gone.getZ())).is(Blocks.DIRT), "dirt stays");
            h.assertTrue(h.getBlockState(new BlockPos(gone.getX(), 2, gone.getZ())).isAir(), "nothing planted on dirt");
        });
    }

    @GameTest(maxTicks = 800)
    public void harvestsMelonKeepsStem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 1, 4, Blocks.FARMLAND);
        h.setBlock(5, 1, 4, Blocks.FARMLAND);
        h.setBlock(4, 2, 4, Blocks.ATTACHED_MELON_STEM.defaultBlockState()
                .setValue(AttachedStemBlock.FACING, Direction.EAST));
        h.setBlock(5, 2, 4, Blocks.MELON);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        FarmPass pass = pass(h, 5, 2, 4, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertTrue(h.getBlockState(new BlockPos(5, 2, 4)).isAir(), "melon harvested");
            h.assertTrue(h.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.MELON_STEM)
                    || h.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.ATTACHED_MELON_STEM), "stem remains");
            h.assertTrue(c.inventory().countItem(Items.MELON_SLICE) > 0, "melon slices collected");
        });
    }

    @GameTest(maxTicks = 800)
    public void harvestsSugarCaneAboveBottom(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 0, 4, Blocks.STONE);
        h.setBlock(4, 1, 4, Blocks.SAND);
        h.setBlock(5, 1, 4, Blocks.WATER);
        for (int y = 2; y <= 4; y++) {
            h.setBlock(4, y, 4, Blocks.SUGAR_CANE);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        FarmPass pass = pass(h, 4, 2, 4, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertTrue(h.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.SUGAR_CANE), "bottom cane remains");
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 4)).isAir(), "middle cane harvested");
            h.assertTrue(h.getBlockState(new BlockPos(4, 4, 4)).isAir(), "top cane harvested");
            h.assertValueEqual(c.inventory().countItem(Items.SUGAR_CANE), 2, "sugar cane collected");
        });
    }

    @GameTest(maxTicks = 800)
    public void plantsGivenCrop(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 4; x <= 5; x++) {
            for (int z = 4; z <= 5; z++) {
                h.setBlock(x, 1, z, Blocks.FARMLAND);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.CARROT, 4));
        FarmPass pass = new FarmPass(h.absolutePos(new BlockPos(5, 2, 5)), 8, Items.CARROT, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertValueEqual(pass.report().planted(), 4, "planted");
            for (int x = 4; x <= 5; x++) {
                for (int z = 4; z <= 5; z++) {
                    h.assertValueEqual(age(h, x, 2, z), 0, "carrot crop planted");
                }
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void tillsNextToWater(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                h.setBlock(x, 1, z, x == 5 && z == 5 ? Blocks.WATER : Blocks.GRASS_BLOCK);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WOODEN_HOE));
        c.inventory().addItem(new ItemStack(Items.BEETROOT_SEEDS, 8));
        FarmPass pass = new FarmPass(h.absolutePos(new BlockPos(5, 2, 5)), 8, null, true);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertTrue(pass.report().tilled() > 0, "tilled " + pass.report().tilled());
            boolean anyBeetroot = false;
            boolean anyFarmland = false;
            for (int x = 4; x <= 6; x++) {
                for (int z = 4; z <= 6; z++) {
                    anyFarmland |= h.getBlockState(new BlockPos(x, 1, z)).is(Blocks.FARMLAND);
                    anyBeetroot |= h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.BEETROOTS);
                }
            }
            h.assertTrue(anyFarmland, "grass turned into farmland");
            h.assertTrue(anyBeetroot, "beetroots planted on the tilled soil");
        });
    }

    @GameTest(maxTicks = 600)
    public void tillWithoutHoe(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                h.setBlock(x, 1, z, x == 5 && z == 5 ? Blocks.WATER : Blocks.GRASS_BLOCK);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        FarmPass pass = new FarmPass(h.absolutePos(new BlockPos(5, 2, 5)), 8, null, true);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertTrue(pass.report().text(8).endsWith("; no hoe to till with"), "text was " + pass.report().text(8));
        });
    }

    @GameTest
    public void reportText(GameTestHelper h) {
        Map<String, Integer> harvested = new LinkedHashMap<>();
        harvested.put("minecraft:wheat", 24);
        harvested.put("minecraft:melon_slice", 3);
        FarmReport report = new FarmReport(harvested, 20, 8, 8, 0, false);
        h.assertValueEqual(report.text(8),
                "harvested 24 minecraft:wheat, 3 minecraft:melon_slice; replanted 20; planted 8 new; tilled 8", "report text");
        h.assertValueEqual(new FarmReport(Map.of(), 0, 0, 0, 0, false).text(8),
                "nothing to harvest or plant within 8 blocks", "empty report text");
        h.succeed();
    }

    private static void wheatPlot(GameTestHelper h, int x1, int z1, int x2, int z2, int age) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                h.setBlock(x, 1, z, Blocks.FARMLAND);
                h.setBlock(x, 2, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age));
            }
        }
    }

    @GameTest(maxTicks = 1200)
    public void farmTaskReports(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 1, 4, Blocks.FARMLAND);
        h.setBlock(5, 1, 4, Blocks.FARMLAND);
        h.setBlock(4, 2, 4, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(5, 2, 4, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new FarmTask(h.absolutePos(new BlockPos(5, 2, 4)), 8, null, false), "farm");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("harvested "), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 200)
    public void farmTaskNothingToDo(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new FarmTask(h.absolutePos(new BlockPos(5, 2, 5)), 8, null, false), "farm");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "nothing to harvest or plant within 8 blocks", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 2400)
    public void tendStoresHarvestInChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        wheatPlot(h, 4, 4, 5, 5, 7);
        h.setBlock(10, 2, 10, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new TendFarmTask(h.absolutePos(new BlockPos(5, 2, 5)), 8, 100), "tend_farm");
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(10, 2, 10)));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(countItem(chest, Items.WHEAT) > 0, "wheat not stored yet");
            h.assertTrue(c.inventory().countItem(Items.WHEAT_SEEDS) <= 64, "kept more than a stack of seeds");
            runner.stop("test");
            h.assertTrue(runner.current().isEmpty(), "tend_farm should stop");
        });
    }

    @GameTest(maxTicks = 2400)
    public void tendWithFullChestNotifiesOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        wheatPlot(h, 4, 4, 5, 5, 7);
        h.setBlock(10, 2, 10, Blocks.CHEST);
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(10, 2, 10)));
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        chest.setChanged();
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        c.taskRunner().setListener(result);
        c.taskRunner().start(new TendFarmTask(h.absolutePos(new BlockPos(5, 2, 5)), 8, 100), "tend_farm");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            h.assertTrue(ticks[0] > 300, "waiting for two passes");
            h.assertValueEqual(result.notices.size(), 1, "notices " + result.notices);
            h.assertTrue(result.notices.get(0).contains("are full"), "notice was " + result.notices.get(0));
        });
    }

    @GameTest
    public void tendFarmRestores(GameTestHelper h) {
        Task task = TaskFactory.restore("tend_farm:1,2,3,8").orElseThrow();
        h.assertValueEqual(task.type(), "tend_farm", "type");
        h.assertValueEqual(task.saveData(), "tend_farm:1,2,3,8", "save data");
        h.assertValueEqual(task.describe(), "tending the farm at 1 2 3 (radius 8)", "describe");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void bonemealGrowsPotatoes(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 4; x <= 6; x++) {
            h.setBlock(x, 1, 4, Blocks.FARMLAND);
            h.setBlock(x, 2, 4, Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 0));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        c.inventory().addItem(new ItemStack(Items.BONE_MEAL, 20));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BonemealTask(h.absolutePos(new BlockPos(5, 2, 4)), 4, 20), "use_bonemeal");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("used "), "summary was " + result.summary);
            boolean ripe = false;
            for (int x = 4; x <= 6; x++) {
                ripe |= h.getBlockState(new BlockPos(x, 2, 4)).getValue(CropBlock.AGE) == 7;
            }
            h.assertTrue(ripe, "no potato matured");
            h.assertTrue(c.inventory().countItem(Items.BONE_MEAL) < 20, "bone meal was not used");
        });
    }

    @GameTest
    public void bonemealWithout(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BonemealTask(h.absolutePos(new BlockPos(5, 2, 5)), 4, 5), "use_bonemeal");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no minecraft:bone_meal", "summary");
        h.succeed();
    }

    private static int countItem(Container container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @GameTest(maxTicks = 600)
    public void farmPassSkipsHarvestWithoutRoom(GameTestHelper h) {
        GameTestSupport.platformAt(h, 260, 16);
        h.setBlock(4, 260, 4, Blocks.FARMLAND);
        h.setBlock(5, 260, 4, Blocks.FARMLAND);
        h.setBlock(4, 261, 4, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(5, 261, 4, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 261, 2);
        for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
            c.inventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        FarmPass pass = new FarmPass(h.absolutePos(new BlockPos(5, 261, 4)), 8, null, false);
        TaskContext ctx = new TaskContext(c, h.getLevel());
        h.succeedWhen(() -> {
            h.assertTrue(pass.tick(ctx), "pass not done yet");
            h.assertTrue(pass.report().harvested().isEmpty(), "harvested " + pass.report().harvested());
            BlockState wheat = h.getBlockState(new BlockPos(4, 261, 4));
            h.assertTrue(wheat.is(Blocks.WHEAT) && wheat.getValue(CropBlock.AGE) == 7, "wheat untouched, was " + wheat);
        });
    }

    @GameTest(maxTicks = 600)
    public void tendWithFullInventoryDoesNotHarvest(GameTestHelper h) {
        GameTestSupport.platformAt(h, 280, 16);
        h.setBlock(4, 280, 4, Blocks.FARMLAND);
        h.setBlock(5, 280, 4, Blocks.FARMLAND);
        h.setBlock(4, 281, 4, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(5, 281, 4, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(10, 281, 10, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 281, 2);
        for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
            c.inventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        c.taskRunner().start(new TendFarmTask(h.absolutePos(new BlockPos(5, 281, 4)), 8, 100), "tend_farm");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            h.assertTrue(ticks[0] > 200, "waiting for a couple of passes");
            BlockState first = h.getBlockState(new BlockPos(4, 281, 4));
            BlockState second = h.getBlockState(new BlockPos(5, 281, 4));
            h.assertTrue(first.is(Blocks.WHEAT) && first.getValue(CropBlock.AGE) == 7, "wheat untouched, was " + first);
            h.assertTrue(second.is(Blocks.WHEAT) && second.getValue(CropBlock.AGE) == 7, "wheat untouched, was " + second);
        });
    }
}
