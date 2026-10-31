package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.acquisition.Source;
import dev.friendai.features.acquisition.ToolKind;
import dev.friendai.features.acquisition.ToolNeed;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.search.DropIndex;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.exploration.minecraft.task.ExploreTask;
import dev.friendai.features.inventory.minecraft.task.CheckContainerTask;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.foundation.item.Tier;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;

public class ObtainTests {
    @GameTest(maxTicks = 1200)
    public void callerObtainsStonePickaxeFromBuriedStone(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 24);
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 194; y < 199; y++) h.setBlock(x, y, z, Blocks.STONE);
                for (int y = 199; y <= 200; y++) h.setBlock(x, y, z, Blocks.DIRT);
            }
        }
        var owner = GameTestSupport.mockPlayerAt(h, 10, 201, 10);
        // Seed one station
        h.setBlock(8, 201, 10, Blocks.CRAFTING_TABLE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 10, 201, 10);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        companion.inventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
        companion.inventory().setItem(2, new ItemStack(Items.STICK, 2));
        var caller = new dev.friendai.execution.tool.Caller(
                owner.getUUID(), owner.getName().getString(), dev.friendai.execution.tool.AccessLevel.OWNER);
        Result result = new Result();
        ExecutionCoordinator runner = obtain(
                companion,
                result,
                obtainTask("minecraft:stone_pickaxe", 1, false, true, 40, 1100, 64, 128, 800, "local", caller));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "caller-aware obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary + " at " + companion.blockPosition());
            h.assertTrue(
                    dev.friendai.task.inventory.InventoryOps.count(
                                    companion.inventory(),
                                    dev.friendai.task.inventory.ItemMatcher.parse("minecraft:stone_pickaxe")
                                            .orElseThrow())
                            > 0,
                    "the companion must actually craft a stone pickaxe from the buried stone");
        });
    }

    @GameTest(maxTicks = 4800)
    public void callerObtainsStonePickaxeFromLogsOnly(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 20);
        for (int x = 6; x <= 12; x++) {
            for (int z = 4; z <= 10; z++) {
                for (int y = 301; y <= 304; y++) h.setBlock(x, y, z, Blocks.STONE);
            }
        }
        var owner = GameTestSupport.mockPlayerAt(h, 2, 301, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 301, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 6));
        for (int y = 301; y <= 304; y++) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, companion, h.absolutePos(new BlockPos(6, y, 6))),
                    "the exposed stone face should be directly visible at y " + y);
        }
        var caller = new dev.friendai.execution.tool.Caller(
                owner.getUUID(), owner.getName().getString(), dev.friendai.execution.tool.AccessLevel.OWNER);
        Result result = new Result();
        ExecutionCoordinator runner = obtain(
                companion,
                result,
                obtainTask("minecraft:stone_pickaxe", 1, false, true, 40, 4400, 64, 128, 800, "local", caller));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "caller-aware obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(
                    dev.friendai.task.inventory.InventoryOps.count(
                                    companion.inventory(),
                                    dev.friendai.task.inventory.ItemMatcher.parse("minecraft:stone_pickaxe")
                                            .orElseThrow())
                            > 0,
                    "the companion must craft a wooden pickaxe, mine stone and craft a stone pickaxe");
        });
    }

    private static final class Result implements TaskEventSink {
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

    private static ObtainTask obtainTask(String item, int count, boolean useContainers, boolean explore) {
        return new ObtainTask(item, count, useContainers, explore, new MinecraftObtainSubtaskFactory());
    }

    private static ObtainTask obtainTask(
            String item,
            int count,
            boolean useContainers,
            boolean explore,
            int maxSteps,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks) {
        return new ObtainTask(
                item,
                count,
                useContainers,
                explore,
                maxSteps,
                maxTicks,
                exploreRadius,
                exploreBlocks,
                exploreTicks,
                "local",
                null,
                null,
                new MinecraftObtainSubtaskFactory());
    }

    private static ObtainTask obtainTask(
            String item,
            int count,
            boolean useContainers,
            boolean explore,
            int maxSteps,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks,
            String searchScope,
            Caller caller) {
        return new ObtainTask(
                item,
                count,
                useContainers,
                explore,
                maxSteps,
                maxTicks,
                exploreRadius,
                exploreBlocks,
                exploreTicks,
                searchScope,
                caller,
                null,
                new MinecraftObtainSubtaskFactory());
    }

    private static ExecutionCoordinator obtain(CompanionEntity c, Result result, ObtainTask task) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 2400, () -> false);
        runner.setEventSink(result);
        runner.start(task, "obtain_item");
        return runner;
    }

    private static ExecutionCoordinator explore(CompanionEntity c, Result result, ExploreTask task) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 600, () -> false);
        runner.setEventSink(result);
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
                .filter(source ->
                        source instanceof Source.Mine mine && mine.block().equals(block))
                .map(source -> (Source.Mine) source)
                .findFirst();
    }

    @GameTest
    public void indexKnowsIronOre(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine =
                mineOf(index, "minecraft:raw_iron", "minecraft:iron_ore").orElse(null);
        h.assertTrue(mine != null, "no iron_ore source for raw_iron: " + index.sourcesOf("minecraft:raw_iron"));
        h.assertTrue(mine.tool().equals(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)), "tool was " + mine.tool());
        h.assertTrue(mine.perBlock() >= 1, "yield was " + mine.perBlock());
        h.assertFalse(mine.surface(), "iron ore is not a surface block");
        h.assertTrue(mine.depthY() == 16, "depth was " + mine.depthY());
        h.succeed();
    }

    @GameTest
    public void indexKnowsStone(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine =
                mineOf(index, "minecraft:cobblestone", "minecraft:stone").orElse(null);
        h.assertTrue(mine != null, "no stone source for cobblestone: " + index.sourcesOf("minecraft:cobblestone"));
        h.assertTrue(mine.tool().equals(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)), "tool was " + mine.tool());
        h.assertTrue(mine.surface(), "stone is a surface block");
        h.succeed();
    }

    @GameTest
    public void indexKnowsLogsByHand(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine =
                mineOf(index, "minecraft:oak_log", "minecraft:oak_log").orElse(null);
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
                .findFirst()
                .orElse(null);
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
    public void scanCountsVisibleBlocksAndMobsWithoutInspectingChests(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 32);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 301, 2);
        h.setBlock(4, 301, 2, Blocks.IRON_ORE);
        h.setBlock(5, 301, 2, Blocks.IRON_ORE);
        h.setBlock(6, 301, 2, Blocks.IRON_ORE);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 8, 301, 8);
        h.setBlock(3, 301, 8, Blocks.CHEST);
        h.setBlock(6, 300, 6, Blocks.SOUL_SAND);
        h.setBlock(7, 300, 6, Blocks.SOUL_SAND);
        h.setBlock(6, 301, 6, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
        h.setBlock(7, 301, 6, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
        h.succeedWhen(() -> {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 301, 2))),
                    "the near iron ore should be directly visible");
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(3, 301, 8))),
                    "the chest should be directly visible");
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 301, 6))),
                    "the mature crop should be directly visible");
            Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 301, 8)));
            h.assertTrue(chest != null, "chest block entity is missing");
            chest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
            chest.setChanged();
            WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, true, false);
            h.assertValueEqual(
                    world.blocksNearby().getOrDefault("minecraft:iron_ore", 0),
                    1,
                    "only the directly observed iron ore is counted");
            h.assertValueEqual(world.mobsNearby().getOrDefault("minecraft:cow", 0), 1, "cows");
            h.assertTrue(world.containerItems().isEmpty(), "scan must not inspect closed containers");
            h.assertValueEqual(
                    world.matureCrops().getOrDefault("minecraft:nether_wart", 0),
                    1,
                    "only the directly observed mature wart is counted");
        });
    }

    @GameTest
    public void scanWithoutContainers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 8, Blocks.CHEST);

        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.containerItems().isEmpty(), "containers were read: " + world.containerItems());
        h.succeed();
    }

    @GameTest
    public void scanOverworldFlag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.dimensionHasOre(), "the overworld has ores");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void worldScanCountsRecentlyObservedLogs(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos log = h.absolutePos(new BlockPos(4, 2, 4));
        h.setBlock(4, 2, 4, Blocks.ACACIA_LOG);
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var level = h.getLevel();
        state.resourceCandidates()
                .observe(
                        c,
                        new dev.friendai.features.knowledge.ResourceObservation(
                                "minecraft:acacia_log",
                                new dev.friendai.features.knowledge.ResourceObservation.Location(
                                        level.dimension().identifier().toString(),
                                        new dev.friendai.features.navigation.pathing.Pos(
                                                log.getX(), log.getY(), log.getZ())),
                                new dev.friendai.features.navigation.pathing.Pos(2, 2, 2),
                                level.getServer().overworld().getGameTime()));
        h.runAfterDelay(20, () -> {
            WorldFacts world = WorldScan.scan(level, c, 32, false, false);
            h.assertValueEqual(
                    world.blocksNearby().getOrDefault("minecraft:acacia_log", 0),
                    1,
                    "a log seen 20 ticks ago should still count as nearby");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 1200)
    public void surfaceExploreFindsLogs(GameTestHelper h) {
        GameTestSupport.platformAt(h, 240, 32);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 241, 2);
        h.setBlock(10, 241, 2, Blocks.DARK_OAK_LOG);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(10, 241, 2))),
                "dark oak log should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = explore(c, result, new ExploreTask(logs(), false, 0, 32, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("found minecraft:dark_oak_log at"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void surfaceExploreGivesUp(GameTestHelper h) {
        GameTestSupport.platformAt(h, 280, 32);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 281, 2);
        Result result = new Result();
        ExecutionCoordinator runner = explore(c, result, new ExploreTask(logs(), false, 0, 16, 128, 6000));
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        Result result = new Result();
        ExecutionCoordinator runner = explore(
                c,
                result,
                new ExploreTask(BlockMatcher.parse("minecraft:gold_ore").orElseThrow(), true, -6, 64, 128, 6000));
        h.succeedWhen(() -> {
            if (h.getBlockState(new BlockPos(2, -6, 10)).is(Blocks.GOLD_ORE))
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(2, -6, 10)));
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        float health = c.getHealth();
        Result result = new Result();
        ExecutionCoordinator runner = explore(c, result, new ExploreTask(logs(), true, -12, 64, 20, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "not finished");
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS || result.status == TaskStatus.FAILED,
                    "unexpected status " + result.status + " " + result.summary);
            h.assertValueEqual(c.getHealth(), health, "health changed");
            for (int x = -1; x <= 8; x++) {
                for (int z = 0; z <= 16; z++) {
                    for (int y = -16; y <= 0; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (h.getLevel().getBlockState(h.absolutePos(pos)).isAir()) {
                            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                                BlockPos next = pos.relative(direction);
                                h.assertFalse(
                                        h.getLevel()
                                                .getBlockState(h.absolutePos(next))
                                                .is(Blocks.LAVA),
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
        BlockPos[] logs = {new BlockPos(4, 2, 2), new BlockPos(4, 2, 7), new BlockPos(8, 2, 4), new BlockPos(8, 2, 6)};
        for (BlockPos log : logs) h.setBlock(log, Blocks.OAK_LOG);
        h.setBlock(8, 2, 8, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (BlockPos log : logs) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(log)),
                    "oak log should be directly visible at " + log);
        }
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 2, 8))),
                "crafting table should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:wooden_pickaxe", 1, true, true));
        int[] factChecks = {0};
        h.succeedWhen(() -> {
            for (BlockPos log : logs) {
                if (h.getBlockState(log).is(Blocks.OAK_LOG)) {
                    h.assertTrue(
                            GameTestSupport.observeVisible(h, c, h.absolutePos(log)),
                            "remaining oak log should still be directly visible during local acquisition: " + log);
                }
            }
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 2, 8))),
                    "crafting table should still be directly visible during local acquisition");
            if (factChecks[0]++ == 0) {
                WorldFacts facts =
                        WorldScan.scan((net.minecraft.server.level.ServerLevel) h.getLevel(), c, 32, true, false);
                h.assertValueEqual(
                        facts.blocksNearby().getOrDefault("minecraft:oak_log", 0),
                        1,
                        "local acquisition facts include the directly observed log");
                h.assertTrue(
                        facts.stationsNearby().contains("crafting table"),
                        "local acquisition facts include the directly observed table: " + facts.stationsNearby());
            }
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("got 1 minecraft:wooden_pickaxe in "), "summary was " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.WOODEN_PICKAXE), 1, "pickaxes");
        });
    }

    @GameTest(maxTicks = 9600)
    public void obtainStonePickaxe(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] logs = {new BlockPos(4, 2, 2), new BlockPos(4, 2, 7), new BlockPos(8, 2, 4), new BlockPos(8, 2, 6)};
        BlockPos[] stones = {new BlockPos(6, 2, 6), new BlockPos(7, 2, 8), new BlockPos(7, 2, 4)};
        for (BlockPos log : logs) h.setBlock(log, Blocks.OAK_LOG);
        for (BlockPos stone : stones) h.setBlock(stone, Blocks.STONE);
        BlockPos table = new BlockPos(12, 2, 12);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (BlockPos pos : logs)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(pos)),
                    "oak log should be directly visible at " + pos);
        for (BlockPos pos : stones)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(pos)),
                    "stone should be directly visible at " + pos);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:stone_pickaxe", 1, true, true));
        h.succeedWhen(() -> {
            for (BlockPos pos : logs)
                if (h.getBlockState(pos).is(Blocks.OAK_LOG)) GameTestSupport.observeVisible(h, c, h.absolutePos(pos));
            for (BlockPos pos : stones)
                if (h.getBlockState(pos).is(Blocks.STONE)) GameTestSupport.observeVisible(h, c, h.absolutePos(pos));
            GameTestSupport.observeVisible(h, c, h.absolutePos(table));
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.STONE_PICKAXE), 1, "pickaxes");
        });
    }

    /**
     * What the companion has seen ages out of the observation window, and this chain is longer than the oak one, so the
     * test keeps its evidence fresh on every tick the way its sibling does.
     */
    @GameTest(maxTicks = 9600)
    public void stonePickaxeFromAcaciaOnly(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] logs = {new BlockPos(4, 2, 2), new BlockPos(4, 2, 7), new BlockPos(8, 2, 4), new BlockPos(8, 2, 6)};
        for (BlockPos log : logs) h.setBlock(log, Blocks.ACACIA_LOG);
        BlockPos[] stones = {new BlockPos(6, 2, 6), new BlockPos(7, 2, 8), new BlockPos(7, 2, 4)};
        for (BlockPos stone : stones) h.setBlock(stone, Blocks.STONE);
        BlockPos table = new BlockPos(12, 2, 12);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (BlockPos pos : logs)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(pos)),
                    "acacia log should be directly visible at " + pos);
        for (BlockPos pos : stones)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(pos)),
                    "stone should be directly visible at " + pos);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:stone_pickaxe", 1, true, true));
        h.succeedWhen(() -> {
            for (BlockPos pos : logs)
                if (h.getBlockState(pos).is(Blocks.ACACIA_LOG))
                    GameTestSupport.observeVisible(h, c, h.absolutePos(pos));
            for (BlockPos pos : stones)
                if (h.getBlockState(pos).is(Blocks.STONE)) GameTestSupport.observeVisible(h, c, h.absolutePos(pos));
            GameTestSupport.observeVisible(h, c, h.absolutePos(table));
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("acacia_log"), result.summary);
            h.assertFalse(result.summary.contains("explored for"), result.summary);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 2, 2))),
                "chest should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 2400, () -> false);
        runner.setEventSink(result);
        runner.start(new CheckContainerTask(h.absolutePos(new BlockPos(4, 2, 2))), "check_container");
        boolean[] startedObtain = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!startedObtain[0] && result.status == TaskStatus.SUCCESS) {
                startedObtain[0] = true;
                result.status = null;
                result.summary = null;
                runner.start(obtainTask("minecraft:stone_pickaxe", 1, true, false), "obtain_item");
            }
            if (!startedObtain[0]) return;
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("got 1 minecraft:stone_pickaxe in 1 steps: took"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 4800)
    public void obtainIronIngot(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.IRON_ORE);
        h.setBlock(6, 2, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 2, 2))),
                "iron ore should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 2))),
                "furnace should be directly visible");
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.COAL));
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:iron_ingot", 1, true, true));
        h.succeedWhen(() -> {
            if (h.getBlockState(new BlockPos(4, 2, 2)).is(Blocks.IRON_ORE)) {
                h.assertTrue(
                        GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 2, 2))),
                        "iron ore should still be directly visible during local acquisition");
            }
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 2))),
                    "furnace should still be directly visible during local acquisition");
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.IRON_INGOT), 1, "iron ingots");
        });
    }

    @GameTest(maxTicks = 9600)
    public void replansAfterTheft(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos[] logs = {new BlockPos(4, 2, 2), new BlockPos(4, 2, 7), new BlockPos(8, 2, 4), new BlockPos(8, 2, 6)};
        for (BlockPos log : logs) h.setBlock(log, Blocks.OAK_LOG);
        BlockPos table = new BlockPos(12, 2, 12);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (BlockPos log : logs)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(log)),
                    "oak log should be directly visible at " + log);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:wooden_pickaxe", 1, true, true));
        boolean[] stolen = {false};
        h.succeedWhen(() -> {
            for (BlockPos log : logs)
                if (h.getBlockState(log).is(Blocks.OAK_LOG))
                    h.assertTrue(
                            GameTestSupport.observeVisible(h, c, h.absolutePos(log)),
                            "remaining log should be visible");
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should be visible");
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
        GameTestSupport.platformAt(h, -40, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -39, 2);
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:iron_ingot", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("can't get 1 minecraft:iron_ingot here: missing:"),
                    "summary was " + result.summary);
            h.assertTrue(result.summary.endsWith("(exploring is off)"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 4800)
    public void exploreFailureEndsObtain(GameTestHelper h) {
        GameTestSupport.platformAt(h, -20, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, -19, 2);
        Result result = new Result();
        ExecutionCoordinator runner =
                obtain(c, result, obtainTask("minecraft:oak_planks", 1, true, true, 40, 24000, 16, 128, 6000));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("stuck at explore"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void alreadyHeldCountsAsObtained(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:stone_pickaxe", 1, true, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("got 1 minecraft:stone_pickaxe in 0 steps"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2400)
    public void stepLimit(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.OAK_LOG);
        h.setBlock(4, 2, 7, Blocks.OAK_LOG);
        h.setBlock(8, 2, 4, Blocks.OAK_LOG);
        h.setBlock(8, 2, 6, Blocks.OAK_LOG);
        BlockPos table = new BlockPos(12, 2, 12);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos[] visibleLogs = {
            new BlockPos(4, 2, 2), new BlockPos(4, 2, 7), new BlockPos(8, 2, 4), new BlockPos(8, 2, 6)
        };
        for (BlockPos log : visibleLogs)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(log)),
                    "oak log should be directly visible at " + log);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(table)),
                "crafting table should be directly visible");
        Result result = new Result();
        ExecutionCoordinator runner =
                obtain(c, result, obtainTask("minecraft:wooden_pickaxe", 1, true, false, 1, 24000, 64, 128, 6000));
        h.succeedWhen(() -> {
            for (BlockPos log : visibleLogs)
                if (h.getBlockState(log).is(Blocks.OAK_LOG))
                    h.assertTrue(
                            GameTestSupport.observeVisible(h, c, h.absolutePos(log)),
                            "remaining log should be visible");
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should remain visible");
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("gave up after 1 steps"), "summary was " + result.summary);
        });
    }

    @GameTest
    public void planObtainText(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(4, 2, 2, Blocks.IRON_ORE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        ToolExecutor tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        String json = "{\"item\":\"minecraft:raw_iron\",\"count\":1}";
        ToolResult r = tools.execute(
                new ToolCall("1", "plan_obtain", JsonParser.parseString(json).getAsJsonObject(), json, null));
        h.assertFalse(r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("mine 1 × minecraft:iron_ore"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void toolMapping(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        h.assertTrue(
                index.toolItem(new ToolNeed(ToolKind.PICKAXE, Tier.STONE))
                        .orElse("")
                        .equals("minecraft:stone_pickaxe"),
                "stone pickaxe item was " + index.toolItem(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)));
        h.assertTrue(
                index.toolOf("minecraft:iron_pickaxe").equals(new ToolNeed(ToolKind.PICKAXE, Tier.IRON)),
                "iron pickaxe was " + index.toolOf("minecraft:iron_pickaxe"));
        h.succeed();
    }
}
