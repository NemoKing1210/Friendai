package dev.friedai.test;

import dev.friedai.crafting.obtain.Source;
import dev.friedai.crafting.obtain.Tier;
import dev.friedai.crafting.obtain.ToolKind;
import dev.friedai.crafting.obtain.ToolNeed;
import com.google.gson.JsonParser;
import dev.friedai.crafting.obtain.WorldFacts;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.ExploreTask;
import dev.friedai.task.builtin.ObtainTask;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.task.obtain.DropIndex;
import dev.friedai.task.obtain.WorldScan;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import dev.friedai.tool.builtin.FriedaiTools;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

public class ObtainTests {
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

    private static TaskRunner obtain(CompanionEntity c, Result result, ObtainTask task) {
        TaskRunner runner = new TaskRunner(c, () -> 2400, () -> false);
        runner.setListener(result);
        runner.start(task, "obtain_item");
        return runner;
    }

    private static TaskRunner explore(CompanionEntity c, Result result, ExploreTask task) {
        TaskRunner runner = new TaskRunner(c, () -> 600, () -> false);
        runner.setListener(result);
        runner.start(task, "explore");
        return runner;
    }

    private static BlockMatcher logs() {
        return BlockMatcher.parse("#minecraft:logs").orElseThrow();
    }

    private static void stoneColumn(GameTestHelper h, int upToZ) {
        for (int x = -1; x <= 8; x++) {
            for (int z = 0; z <= upToZ; z++) {
                for (int y = -16; y <= 0; y++) {
                    h.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
    }

    private static Optional<Source.Mine> mineOf(DropIndex index, String item, String block) {
        List<Source> sources = index.sourcesOf(item);
        return sources.stream()
                .filter(source -> source instanceof Source.Mine mine && mine.block().equals(block))
                .map(source -> (Source.Mine) source)
                .findFirst();
    }

    @GameTest
    public void indexKnowsIronOre(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine = mineOf(index, "minecraft:raw_iron", "minecraft:iron_ore").orElse(null);
        h.assertTrue(mine != null, "no iron_ore source for raw_iron: " + index.sourcesOf("minecraft:raw_iron"));
        h.assertTrue(mine.tool().equals(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)),
                "tool was " + mine.tool());
        h.assertTrue(mine.perBlock() >= 1, "yield was " + mine.perBlock());
        h.assertFalse(mine.surface(), "iron ore is not a surface block");
        h.assertTrue(mine.depthY() == 16, "depth was " + mine.depthY());
        h.succeed();
    }

    @GameTest
    public void indexKnowsStone(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine = mineOf(index, "minecraft:cobblestone", "minecraft:stone").orElse(null);
        h.assertTrue(mine != null, "no stone source for cobblestone: " + index.sourcesOf("minecraft:cobblestone"));
        h.assertTrue(mine.tool().equals(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)),
                "tool was " + mine.tool());
        h.assertTrue(mine.surface(), "stone is a surface block");
        h.succeed();
    }

    @GameTest
    public void indexKnowsLogsByHand(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine = mineOf(index, "minecraft:oak_log", "minecraft:oak_log").orElse(null);
        h.assertTrue(mine != null, "no oak_log source: " + index.sourcesOf("minecraft:oak_log"));
        h.assertTrue(mine.tool().equals(ToolNeed.HAND), "tool was " + mine.tool());
        h.assertTrue(mine.surface(), "logs are a surface resource");
        h.assertTrue(Math.abs(mine.perBlock() - 1.0) < 0.0001, "yield was " + mine.perBlock());
        h.succeed();
    }

    @GameTest
    public void indexKnowsLeather(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Hunt hunt = index.sourcesOf("minecraft:leather").stream()
                .filter(source -> source instanceof Source.Hunt)
                .map(source -> (Source.Hunt) source)
                .filter(source -> source.mob().equals("minecraft:cow"))
                .findFirst().orElse(null);
        h.assertTrue(hunt != null, "no cow hunt source for leather: " + index.sourcesOf("minecraft:leather"));
        h.assertTrue(hunt.perKill() > 0, "yield was " + hunt.perKill());
        h.succeed();
    }

    @GameTest
    public void indexBuiltOnce(GameTestHelper h) {
        h.assertTrue(DropIndex.of(h.getLevel()) == DropIndex.of(h.getLevel()), "index was rebuilt");
        h.succeed();
    }

    @GameTest
    public void scanCountsBlocksMobsChests(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 32);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 301, 2);
        h.setBlock(4, 301, 2, Blocks.IRON_ORE);
        h.setBlock(5, 301, 2, Blocks.IRON_ORE);
        h.setBlock(6, 301, 2, Blocks.IRON_ORE);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 8, 301, 8);
        h.setBlock(3, 301, 8, Blocks.CHEST);
        h.setBlock(6, 300, 6, Blocks.FARMLAND);
        h.setBlock(7, 300, 6, Blocks.FARMLAND);
        h.setBlock(6, 301, 6, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(7, 301, 6, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));

        h.succeedWhen(() -> {
            Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 301, 8)));
            h.assertTrue(chest != null, "chest block entity is missing");
            chest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
            chest.setChanged();
            WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, true, false);
            h.assertValueEqual(world.blocksNearby().getOrDefault("minecraft:iron_ore", 0), 3, "iron ore blocks");
            h.assertValueEqual(world.mobsNearby().getOrDefault("minecraft:cow", 0), 1, "cows");
            h.assertValueEqual(world.containerItems().getOrDefault("minecraft:iron_ingot", 0), 2, "chest ingots");
            h.assertValueEqual(world.matureCrops().getOrDefault("minecraft:wheat", 0), 2, "mature wheat");
        });
    }

    @GameTest
    public void scanWithoutContainers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 8, Blocks.CHEST);

        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.containerItems().isEmpty(), "containers were read: " + world.containerItems());
        h.succeed();
    }

    @GameTest
    public void scanOverworldFlag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.dimensionHasOre(), "the overworld has ores");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void surfaceExploreFindsLogs(GameTestHelper h) {
        GameTestSupport.platformAt(h, 240, 32);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 241, 2);
        h.setBlock(22, 241, 2, Blocks.DARK_OAK_LOG);
        Result result = new Result();
        TaskRunner runner = explore(c, result, new ExploreTask(logs(), false, 0, 32, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("found minecraft:dark_oak_log at"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void surfaceExploreGivesUp(GameTestHelper h) {
        GameTestSupport.platformAt(h, 280, 32);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 281, 2);
        Result result = new Result();
        TaskRunner runner = explore(c, result, new ExploreTask(logs(), false, 0, 16, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("no #minecraft:logs within 16 blocks"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2400)
    public void staircaseFindsOre(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        stoneColumn(h, 16);
        h.setBlock(2, -6, 10, Blocks.GOLD_ORE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        Result result = new Result();
        TaskRunner runner = explore(c, result,
                new ExploreTask(BlockMatcher.parse("minecraft:gold_ore").orElseThrow(), true, -6, 64, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("found minecraft:gold_ore at"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2400)
    public void staircaseAvoidsLava(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        stoneColumn(h, 16);
        h.setBlock(2, -1, 5, Blocks.LAVA);
        h.setBlock(3, -1, 5, Blocks.LAVA);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        float health = c.getHealth();
        Result result = new Result();
        TaskRunner runner = explore(c, result,
                new ExploreTask(logs(), true, -12, 64, 20, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "not finished");
            h.assertTrue(result.status == TaskStatus.SUCCESS || result.status == TaskStatus.FAILED,
                    "unexpected status " + result.status + " " + result.summary);
            h.assertValueEqual(c.getHealth(), health, "health changed");
            for (int x = -1; x <= 8; x++) {
                for (int z = 0; z <= 16; z++) {
                    for (int y = -16; y <= 0; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (h.getLevel().getBlockState(h.absolutePos(pos)).isAir()) {
                            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                                BlockPos next = pos.relative(direction);
                                h.assertFalse(h.getLevel().getBlockState(h.absolutePos(next)).is(Blocks.LAVA),
                                        "dug next to lava at " + pos);
                            }
                        }
                    }
                }
            }
        });
    }

    @GameTest(maxTicks = 4800)
    public void obtainWoodenPickaxe(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.OAK_LOG);
        h.setBlock(5, 2, 2, Blocks.OAK_LOG);
        h.setBlock(4, 2, 3, Blocks.OAK_LOG);
        h.setBlock(5, 2, 3, Blocks.OAK_LOG);
        h.setBlock(8, 2, 8, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:wooden_pickaxe", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("got 1 minecraft:wooden_pickaxe in "), "summary was " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.WOODEN_PICKAXE), 1, "pickaxes");
        });
    }

    @GameTest(maxTicks = 9600)
    public void obtainStonePickaxe(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.OAK_LOG);
        h.setBlock(5, 2, 2, Blocks.OAK_LOG);
        h.setBlock(4, 2, 3, Blocks.OAK_LOG);
        h.setBlock(5, 2, 3, Blocks.OAK_LOG);
        h.setBlock(8, 2, 8, Blocks.CRAFTING_TABLE);
        h.setBlock(6, 2, 2, Blocks.STONE);
        h.setBlock(6, 2, 3, Blocks.STONE);
        h.setBlock(6, 2, 4, Blocks.STONE);
        h.setBlock(7, 2, 2, Blocks.STONE);
        h.setBlock(7, 2, 3, Blocks.STONE);
        h.setBlock(7, 2, 4, Blocks.STONE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:stone_pickaxe", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.STONE_PICKAXE), 1, "pickaxes");
        });
    }

    @GameTest(maxTicks = 2400)
    public void obtainTakesFromChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.CHEST);
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 2, 2)));
        chest.setItem(0, new ItemStack(Items.STONE_PICKAXE));
        chest.setChanged();
        h.setBlock(6, 2, 2, Blocks.STONE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:stone_pickaxe", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("got 1 minecraft:stone_pickaxe in 1 steps: took"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 4800)
    public void obtainIronIngot(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.IRON_ORE);
        h.setBlock(6, 2, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.COAL));
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:iron_ingot", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.IRON_INGOT), 1, "iron ingots");
        });
    }

    @GameTest(maxTicks = 9600)
    public void replansAfterTheft(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 4; x <= 6; x++) {
            for (int z = 2; z <= 3; z++) {
                h.setBlock(x, 2, z, Blocks.OAK_LOG);
            }
        }
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:wooden_pickaxe", 1, true, false));
        boolean[] stolen = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!stolen[0] && c.inventory().countItem(Items.OAK_LOG) > 0) {
                for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
                    if (c.inventory().getItem(slot).is(Items.OAK_LOG)) {
                        c.inventory().setItem(slot, ItemStack.EMPTY);
                    }
                }
                stolen[0] = true;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
        });
    }

    @GameTest(maxTicks = 2400)
    public void obtainMissingWithoutExplore(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:iron_ingot", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("can't get 1 minecraft:iron_ingot here: missing:"),
                    "summary was " + result.summary);
            h.assertTrue(result.summary.endsWith("(exploring is off)"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 4800)
    public void exploreFailureEndsObtain(GameTestHelper h) {
        GameTestSupport.platformAt(h, -20, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, -19, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result, new ObtainTask("minecraft:oak_planks", 1, true, true, 40, 24000, 16, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("stuck at explore"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2400)
    public void stepLimit(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.OAK_LOG);
        h.setBlock(5, 2, 2, Blocks.OAK_LOG);
        h.setBlock(4, 2, 3, Blocks.OAK_LOG);
        h.setBlock(5, 2, 3, Blocks.OAK_LOG);
        h.setBlock(8, 2, 8, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = obtain(c, result,
                new ObtainTask("minecraft:wooden_pickaxe", 1, true, false, 1, 24000, 64, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("gave up after 1 steps"), "summary was " + result.summary);
        });
    }

    @GameTest
    public void planObtainText(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.IRON_ORE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        ToolExecutor tools = FriedaiTools.create(() -> 32).bind(c);
        String json = "{\"item\":\"minecraft:raw_iron\",\"count\":1}";
        ToolResult r = tools.execute(new ToolCall("1", "plan_obtain",
                JsonParser.parseString(json).getAsJsonObject(), json, null));
        h.assertFalse(r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("mine 1 × minecraft:iron_ore"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void toolMapping(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        h.assertTrue(index.toolItem(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)).orElse("").equals("minecraft:stone_pickaxe"),
                "stone pickaxe item was " + index.toolItem(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)));
        h.assertTrue(index.toolOf("minecraft:iron_pickaxe").equals(new ToolNeed(ToolKind.PICKAXE, Tier.IRON)),
                "iron pickaxe was " + index.toolOf("minecraft:iron_pickaxe"));
        h.succeed();
    }
}
