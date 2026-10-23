package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.TaskContext;
import dev.friedai.task.farming.FarmPass;
import dev.friedai.task.farming.FarmReport;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class FarmingTests {
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 5);
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
                h.assertValueEqual(age(h, ring[index].getX(), 2, ring[index].getZ()), 3, "immature plot untouched");
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 5);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 4);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 4);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
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
}
