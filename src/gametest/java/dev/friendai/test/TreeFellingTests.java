package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.minecraft.task.FellLogsTask;
import dev.friendai.features.mining.minecraft.task.FellTreeTask;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.block.BlockMatcher;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;

/**
 * Tree recognition and felling. The world is shared and tests run in parallel, so each test uses its own wood types
 * and looks only at the trees of those types.
 */
public class TreeFellingTests {
    private static ToolExecutor tools(CompanionEntity companion) {
        var state = GameTestSupport.runtime(companion.level().getServer());
        return FriendaiTools.build(
                        () -> 32,
                        () -> 32,
                        dev.friendai.features.memory.minecraft.tool.MemoryAccess.DISABLED,
                        () -> new PerceptionContext(state.resourceCandidates(), state.settings(), state.freeTime()),
                        () -> true,
                        () -> true,
                        state.resourceCandidates(),
                        state.tradingAccess())
                .registry()
                .bind(companion);
    }

    /** Persistent leaves, so they do not decay while the test waits. */
    private static void leaf(GameTestHelper h, int x, int y, int z) {
        h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
    }

    private static void observeFromBeside(GameTestHelper h, CompanionEntity companion, BlockPos target) {
        BlockPos absolute = h.absolutePos(target);
        companion.teleportTo(absolute.getX() + 1.5, absolute.getY(), absolute.getZ() + 0.5);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, absolute),
                "block should be visible from beside " + absolute + " from " + companion.blockPosition());
    }

    private static List<TreeFinder.TreeInfo> ofType(List<TreeFinder.TreeInfo> found, String... types) {
        List<String> wanted = List.of(types);
        return found.stream().filter(t -> wanted.contains(t.type())).toList();
    }

    private static TreeFinder.Kind kindOf(List<TreeFinder.TreeInfo> found, String type) {
        return found.stream()
                .filter(t -> t.type().equals(type))
                .findFirst()
                .orElseThrow()
                .kind();
    }

    private static ToolResult run(ToolExecutor tools, Caller caller, String name, String json) {
        return tools.execute(
                new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null), caller);
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

    private static TreeFinder.TreeInfo treeAt(GameTestHelper h, int x, int z) {
        BlockPos origin = h.absolutePos(new BlockPos(x, 2, z));
        return TreeFinder.find(h.getLevel(), origin, 16, 16, 32, null, 1, true).get(0);
    }

    private static ExecutionCoordinator start(CompanionEntity c, Result result, FellTreeTask task) {
        return start(c, result, task, 300);
    }

    private static ExecutionCoordinator start(CompanionEntity c, Result result, FellTreeTask task, int timeoutSec) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> timeoutSec, () -> false);
        runner.setEventSink(result);
        runner.start(task, "fell_tree");
        return runner;
    }

    private static BlockMatcher oak() {
        return BlockMatcher.parse("minecraft:oak_log").orElseThrow();
    }

    /** Counts dirt in the arena: the only dirt there is scaffold this task placed. */
    private static int dirtLeft(GameTestHelper h) {
        int left = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 2; y <= 30; y++) {
                    if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT)) {
                        left++;
                    }
                }
            }
        }
        return left;
    }

    @GameTest(maxTicks = 7000)
    public void tallDarkOakFallsFromTheCrown(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        java.util.List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 21; y++) {
            for (int x = 8; x <= 9; x++) {
                for (int z = 8; z <= 9; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    h.setBlock(p, Blocks.DARK_OAK_LOG);
                    logs.add(p);
                }
            }
        }
        for (int x = 6; x <= 11; x++) {
            for (int z = 6; z <= 11; z++) {
                BlockPos p = new BlockPos(x, 22, z);
                if (h.getBlockState(p).isAir()) {
                    h.setBlock(p, Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 64));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        List<BlockPos> absolute = logs.stream().map(h::absolutePos).toList();
        TreeFinder.TreeInfo tree = new TreeFinder.TreeInfo(
                "dark_oak_log", absolute, absolute.get(0), 20, TreeFinder.Kind.STANDING_TREE, 8.0, "south-east");
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        tree, BlockMatcher.parse("minecraft:dark_oak_log").orElseThrow(), 0, true),
                300);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : logs) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p);
            }
            h.assertTrue(result.summary.contains("80 of 80"), "summary " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.DARK_OAK_LOG), 80, "drops");
            h.assertValueEqual(dirtLeft(h), 0, "scaffold left in the world");
            h.assertTrue(
                    c.blockPosition().getY()
                            <= h.absolutePos(new BlockPos(0, 3, 0)).getY(),
                    "ended on the ground: " + c.blockPosition() + " origin " + h.absolutePos(BlockPos.ZERO) + " below "
                            + h.getLevel().getBlockState(c.blockPosition().below()));
        });
    }

    @GameTest(maxTicks = 800)
    public void aTreeWithNoUsableClimbIsCutFromTheGround(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            BlockPos p = new BlockPos(6, y, 6);
            h.setBlock(p, Blocks.OAK_LOG);
            logs.add(p);
        }
        // Wall every climb column the planner may pick, leaving only the companion's own column open: it has no
        // material to pillar with and no side of the trunk is reachable, so the logs must be cut from the ground.
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                if ((x == 6 && z == 6) || (x == 5 && z == 6)) {
                    continue;
                }
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 5, 2, 6);
        List<BlockPos> absolute = logs.stream().map(h::absolutePos).toList();
        TreeFinder.TreeInfo tree = new TreeFinder.TreeInfo(
                "oak_log", absolute, absolute.get(0), 3, TreeFinder.Kind.STANDING_TREE, 8.0, "south");
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(tree, oak(), 0, true));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("3 of 3"), "summary " + result.summary);
        });
    }

    @GameTest(maxTicks = 800)
    public void safelyCutsTreeLogUnderItsFeet(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos log = new BlockPos(6, 2, 6);
        h.setBlock(log, Blocks.OAK_LOG);
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                if (x != 6 || z != 6) {
                    h.setBlock(new BlockPos(x, 2, z), Blocks.DIRT);
                    h.setBlock(new BlockPos(x, 3, z), Blocks.DIRT);
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 3, 6);
        BlockPos absolute = h.absolutePos(log);
        TreeFinder.TreeInfo tree = new TreeFinder.TreeInfo(
                "oak_log", List.of(absolute), absolute, 1, TreeFinder.Kind.STANDING_TREE, 0, "south");
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(tree, oak(), 0, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(log).isAir(), "log remains under companion");
        });
    }

    @GameTest(maxTicks = 400)
    public void shortTreeWithoutScaffoldCutsReachableLogsFromTheBase(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 7; y++) {
            BlockPos pos = new BlockPos(6, y, 6);
            h.setBlock(pos, Blocks.OAK_LOG);
            logs.add(pos);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 5, 2, 6);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(info(h, logs, "oak_log", 6), oak(), 4, false));
        List<BlockPos> broken = new java.util.ArrayList<>();
        h.succeedWhen(() -> {
            runner.tick();
            for (BlockPos log : logs) {
                if (h.getBlockState(log).isAir() && !broken.contains(log)) {
                    broken.add(log);
                }
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(broken.equals(logs.subList(0, 4)), "wrong cut order: " + broken);
        });
    }

    @GameTest(maxTicks = 1600)
    public void shortTreeWithScaffoldIsCutFromTheBase(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 7; y++) {
            BlockPos pos = new BlockPos(6, y, 6);
            h.setBlock(pos, Blocks.OAK_LOG);
            logs.add(pos);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(info(h, logs, "oak_log", 6), oak(), 0, false));
        List<BlockPos> broken = new java.util.ArrayList<>();
        h.succeedWhen(() -> {
            runner.tick();
            for (BlockPos log : logs) {
                if (h.getBlockState(log).isAir() && !broken.contains(log)) {
                    broken.add(log);
                }
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(broken.equals(logs), "wrong cut order: " + broken);
        });
    }

    @GameTest(maxTicks = 1600)
    public void fellsWholeTree(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 6; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
        }
        leaf(h, 5, 6, 6);
        leaf(h, 7, 6, 6);
        leaf(h, 6, 6, 5);
        leaf(h, 6, 6, 7);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        FellTreeTask task = new FellTreeTask(treeAt(h, 6, 6), oak(), 0, true);
        ExecutionCoordinator runner = start(c, result, task);
        var work = task.debugWorkSets().getFirst();
        h.assertValueEqual(work.total(), 5, "full tree work-set size");
        h.assertValueEqual(work.pending() + work.next(), 5, "planned tree logs before work starts");
        boolean[] sawTwoDone = {false};
        h.succeedWhen(() -> {
            runner.tick();
            var progress = task.debugWorkSets().getFirst();
            if (progress.done() == 2) {
                sawTwoDone[0] = true;
                h.assertValueEqual(progress.next(), 1, "next log after two cuts");
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            var finished = task.debugWorkSets().getFirst();
            h.assertValueEqual(finished.done(), 5, "completed tree work-set count");
            h.assertTrue(sawTwoDone[0], "did not observe the two-log work-set transition");
            for (int y = 2; y <= 6; y++) {
                h.assertTrue(h.getBlockState(new BlockPos(6, y, 6)).isAir(), "log left at y " + y);
            }
            h.assertValueEqual(c.inventory().countItem(Items.OAK_LOG), 5, "drops collected");
        });
    }

    @GameTest(maxTicks = 4000)
    public void tallTreeLeavesNoScaffoldBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 10; y++) {
            h.setBlock(new BlockPos(12, y, 12), Blocks.OAK_LOG);
        }
        leaf(h, 11, 10, 12);
        leaf(h, 13, 10, 12);
        leaf(h, 12, 10, 11);
        leaf(h, 12, 10, 13);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(treeAt(h, 12, 12), oak(), 0, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            StringBuilder left = new StringBuilder();
            for (int x = 8; x <= 15; x++) {
                for (int y = 2; y <= 12; y++) {
                    for (int z = 8; z <= 15; z++) {
                        if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_LOG)) {
                            left.append(' ')
                                    .append(x)
                                    .append(',')
                                    .append(y)
                                    .append(',')
                                    .append(z);
                        }
                    }
                }
            }
            h.assertTrue(left.isEmpty(), "oak logs left at:" + left + "; summary " + result.summary);
            h.assertValueEqual(dirtLeft(h), 0, "scaffold left in the world; summary " + result.summary);
        });
    }

    @GameTest(maxTicks = 4000)
    public void startsAtTheTopLog(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 10; y++) {
            h.setBlock(new BlockPos(8, y, 8), Blocks.OAK_LOG);
        }
        leaf(h, 7, 10, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(treeAt(h, 8, 8), oak(), 1, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(8, 10, 8)).isAir(), "the top log must be the one cut");
            h.assertTrue(h.getBlockState(new BlockPos(8, 2, 8)).is(Blocks.OAK_LOG), "the bottom log must stay");
        });
    }

    @GameTest(maxTicks = 1600)
    public void limitStopsEarly(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 6; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
        }
        leaf(h, 5, 6, 6);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(treeAt(h, 6, 6), oak(), 2, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 6)).isAir(), "the bottom log must be cut");
            h.assertTrue(h.getBlockState(new BlockPos(6, 3, 6)).isAir(), "the second log must be cut");
            h.assertTrue(h.getBlockState(new BlockPos(6, 6, 6)).is(Blocks.OAK_LOG), "the rest must stay");
            h.assertTrue(result.summary.contains("(limit)"), "summary " + result.summary);
        });
    }

    @GameTest(maxTicks = 1600)
    public void finishesFloatingLeftovers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(6, 5, 6), Blocks.OAK_LOG);
        h.setBlock(new BlockPos(6, 6, 6), Blocks.OAK_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(treeAt(h, 6, 6), oak(), 0, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 5, 6)).isAir(), "lower log left");
            h.assertTrue(h.getBlockState(new BlockPos(6, 6, 6)).isAir(), "upper log left");
        });
    }

    @GameTest(maxTicks = 1600)
    public void reportsPartialWhenALogIsProtected(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
        }
        leaf(h, 6, 6, 6);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        BlockPos protectedLog = h.absolutePos(new BlockPos(6, 5, 6));
        c.hands().overrideProtection(p -> p.equals(protectedLog));
        Result result = new Result();
        ExecutionCoordinator runner = start(c, result, new FellTreeTask(treeAt(h, 6, 6), oak(), 0, false));
        h.succeedWhen(() -> {
            runner.tick();
            // The logs that came down are real work: one log that cannot be cut is a named shortfall, not a failure.
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("protected by the server at"), "summary " + result.summary);
            h.assertTrue(!result.summary.contains("unreachable at"), "summary " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 5, 6)).is(Blocks.OAK_LOG), "the protected log must stay");
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 6)).isAir(), "the reachable logs must be cut");
        });
    }

    /** One protected log of nine must not discard the eight that came down; the shortfall names its real reason. */
    @GameTest(maxTicks = 4000)
    public void oneProtectedLogOfNineStillSucceeds(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 10; y++) {
            BlockPos p = new BlockPos(6, y, 6);
            h.setBlock(p, Blocks.BIRCH_LOG);
            logs.add(p);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 32));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        BlockPos blocked = h.absolutePos(new BlockPos(6, 10, 6));
        c.hands().overrideProtection(p -> p.equals(blocked));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, logs, "birch_log", 9),
                        BlockMatcher.parse("minecraft:birch_log").orElseThrow(),
                        0,
                        false),
                400);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("8 of 9"), "summary " + result.summary);
            h.assertTrue(result.summary.contains("left 1 protected by the server at"), "summary " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 10, 6)).is(Blocks.BIRCH_LOG),
                    "the log that cannot be cut must stay");
            for (int y = 2; y <= 9; y++) {
                h.assertTrue(
                        h.getBlockState(new BlockPos(6, y, 6)).isAir(), "log left at y " + y + "; " + result.summary);
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void doesNotFellALogStructure(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 9; x <= 13; x++) {
            for (int z = 10; z <= 12; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
            }
        }
        // A small radius keeps other tests' trees out of the search in the shared world.
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        for (int x = 9; x <= 13; x++) {
            for (int y = 2; y <= 4; y++) {
                h.assertTrue(
                        GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(x, y, 10))),
                        "front face of the log structure should be visible at " + x + " " + y + " 10");
            }
        }
        var tools = tools(c);
        var anonymous = new Caller(null, "", AccessLevel.TRUSTED);
        h.assertTrue(
                run(tools, anonymous, "fell_tree", "{\"near\":\"self\",\"radius\":6}")
                        .error(),
                "a structure must be refused");
        h.assertTrue(
                run(tools, anonymous, "fell_tree", "{\"near\":\"self\",\"radius\":6,\"type\":\"#minecraft:logs\"}")
                        .error(),
                "a tag must not pick a structure");
        var named =
                run(tools, anonymous, "fell_tree", "{\"near\":\"self\",\"radius\":6,\"type\":\"minecraft:oak_log\"}");
        h.assertTrue(!named.error() && named.content().startsWith("task #"), named.content());
        h.assertTrue(
                c.executionCoordinator()
                        .current()
                        .map(t -> t.type().equals("fell_tree"))
                        .orElse(false),
                "task should start");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void fellsTreeNearTheSpeaker(GameTestHelper h) {
        GameTestSupport.platform(h, 20);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(16, y, 16), Blocks.OAK_LOG);
        }
        leaf(h, 16, 6, 16);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(2, y, 2), Blocks.OAK_LOG);
        }
        leaf(h, 2, 6, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 15.5, 2, 15.5);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        c.setOwner(owner.getUUID());
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(16, 2, 16))),
                "speaker's tree should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(2, 2, 2))),
                "nearby tree should be directly visible");
        BlockPos speakers = h.absolutePos(new BlockPos(16, 2, 16));
        BlockPos nearCompanion = h.absolutePos(new BlockPos(2, 2, 2));
        var result = run(
                tools(c),
                new Caller(owner.getUUID(), "owner", AccessLevel.OWNER),
                "fell_tree",
                "{\"near\":\"me\",\"radius\":6}");
        h.assertTrue(!result.error(), result.content());
        String task = c.executionCoordinator().current().orElseThrow().describe();
        h.assertTrue(task.contains(speakers.getX() + " " + speakers.getY() + " " + speakers.getZ()), task);
        h.assertTrue(
                !task.contains(nearCompanion.getX() + " " + nearCompanion.getY() + " " + nearCompanion.getZ()), task);
        h.succeed();
    }

    /** Two oak trees: one by the owner, one by the companion, both in sight. */
    private static CompanionEntity twoTreeArena(GameTestHelper h, double ownerX, double ownerY, double ownerZ) {
        var owner = GameTestSupport.mockPlayerAt(h, ownerX, ownerY, ownerZ);
        return twoTreeArena(h, owner.getUUID());
    }

    private static CompanionEntity twoTreeArena(GameTestHelper h, java.util.UUID owner) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(3, y, 3), Blocks.OAK_LOG);
            h.setBlock(new BlockPos(12, y, 12), Blocks.OAK_LOG);
        }
        leaf(h, 2, 5, 3);
        leaf(h, 4, 5, 3);
        leaf(h, 3, 5, 2);
        leaf(h, 3, 5, 4);
        leaf(h, 11, 5, 12);
        leaf(h, 13, 5, 12);
        leaf(h, 12, 5, 11);
        leaf(h, 12, 5, 13);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 14, 2, 12);
        c.setOwner(owner);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(3, 2, 3))),
                "the owner's tree should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(12, 2, 12))),
                "the companion's tree should be directly visible");
        return c;
    }

    private static String what(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** The search origin is the companion: no {@code near} must never mean the owner's position. */
    @GameTest(maxTicks = 400)
    public void defaultsToTheTreeNearTheCompanion(GameTestHelper h) {
        CompanionEntity c = twoTreeArena(h, 1.5, 2, 1.5);
        var owner = new Caller(c.ownerUuid(), "owner", AccessLevel.OWNER);
        BlockPos nearOwner = h.absolutePos(new BlockPos(3, 2, 3));
        BlockPos nearCompanion = h.absolutePos(new BlockPos(12, 2, 12));
        String where = "companion " + what(c.blockPosition()) + ", trees " + what(nearOwner) + " (owner side) and "
                + what(nearCompanion) + " (companion side)";
        ToolResult listed = run(tools(c), owner, "find_trees", "{}");
        h.assertTrue(!listed.error(), where + "; find_trees: " + listed.content());
        h.assertTrue(
                listed.content().indexOf(what(nearCompanion)) >= 0
                        && (listed.content().indexOf(what(nearOwner)) < 0
                                || listed.content().indexOf(what(nearCompanion))
                                        < listed.content().indexOf(what(nearOwner))),
                where + "; find_trees listed: " + listed.content());
        ToolResult started = run(tools(c), owner, "fell_tree", "{}");
        h.assertTrue(!started.error(), where + "; fell_tree: " + started.content());
        String task = c.executionCoordinator().current().orElseThrow().describe();
        h.assertTrue(task.contains(what(nearCompanion)), where + "; chose " + task);
        h.assertTrue(!task.contains(what(nearOwner)), where + "; chose " + task);
        h.succeed();
    }

    /** An owner more than 64 blocks away is not an origin: the job starts around the companion. */
    @GameTest(maxTicks = 400)
    public void startsWhenTheOwnerIsFarAway(GameTestHelper h) {
        CompanionEntity c = twoTreeArena(h, 100.5, 2, 100.5);
        var owner = new Caller(c.ownerUuid(), "owner", AccessLevel.OWNER);
        BlockPos nearCompanion = h.absolutePos(new BlockPos(12, 2, 12));
        ToolResult started = run(tools(c), owner, "fell_tree", "{}");
        h.assertTrue(!started.error(), "owner 100 blocks away: " + started.content());
        String task = c.executionCoordinator().current().orElseThrow().describe();
        h.assertTrue(task.contains(what(nearCompanion)), "chose " + task);
        h.succeed();
    }

    /** An owner who is offline cannot be the origin either: a reflex plan has no speaker at all. */
    @GameTest(maxTicks = 400)
    public void startsWhenTheOwnerIsOffline(GameTestHelper h) {
        CompanionEntity c = twoTreeArena(h, java.util.UUID.randomUUID());
        var anonymous = new Caller(null, "", AccessLevel.TRUSTED);
        BlockPos nearCompanion = h.absolutePos(new BlockPos(12, 2, 12));
        ToolResult started = run(tools(c), anonymous, "fell_tree", "{}");
        h.assertTrue(!started.error(), "the owner is offline: " + started.content());
        String task = c.executionCoordinator().current().orElseThrow().describe();
        h.assertTrue(task.contains(what(nearCompanion)), "chose " + task);
        h.succeed();
    }

    /** "near me" still needs a speaker; without one the model is told how to proceed, not sent to the owner. */
    @GameTest(maxTicks = 400)
    public void nearMeWithoutASpeakerIsRefused(GameTestHelper h) {
        CompanionEntity c = twoTreeArena(h, 1.5, 2, 1.5);
        var anonymous = new Caller(null, "", AccessLevel.TRUSTED);
        ToolResult result = run(tools(c), anonymous, "fell_tree", "{\"near\":\"me\"}");
        h.assertTrue(result.error(), "near me without a speaker must be refused: " + result.content());
        h.assertTrue(result.content().contains("near \"self\""), result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 1600)
    public void mineBlocksNearTheSpeaker(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(10, 2, 10), Blocks.DARK_PRISMARINE); // next to the speaker
        h.setBlock(new BlockPos(5, 2, 5), Blocks.DARK_PRISMARINE); // next to the companion
        var owner = GameTestSupport.mockPlayerAt(h, 9.5, 2, 9.5);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(net.minecraft.world.item.Items.IRON_PICKAXE.getDefaultInstance());
        c.setOwner(owner.getUUID());
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(10, 2, 10))),
                "speaker's block should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(5, 2, 5))),
                "nearby block should be directly visible");
        var started = run(
                tools(c),
                new Caller(owner.getUUID(), "owner", AccessLevel.OWNER),
                "mine_blocks",
                "{\"block\":\"minecraft:dark_prismarine\",\"count\":1,\"near\":\"me\"}");
        h.assertTrue(!started.error(), started.content());
        h.succeedWhen(() -> {
            h.assertTrue(h.getBlockState(new BlockPos(10, 2, 10)).isAir(), "the speaker's block must be mined");
            h.assertTrue(
                    h.getBlockState(new BlockPos(5, 2, 5)).is(Blocks.DARK_PRISMARINE), "the other block must stay");
        });
    }

    @GameTest(maxTicks = 400)
    public void findTreesListsTheNearest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.MANGROVE_LOG);
        }
        leaf(h, 6, 6, 6);
        for (int y = 2; y <= 4; y++) {
            h.setBlock(new BlockPos(12, y, 12), Blocks.DARK_OAK_LOG);
        }
        leaf(h, 12, 5, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 8);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 6))),
                "nearest tree should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(12, 2, 12))),
                "farther tree should be directly visible");
        ToolResult result = run(
                tools(c), new Caller(null, "", AccessLevel.TRUSTED), "find_trees", "{\"near\":\"self\",\"radius\":16}");
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("mangrove_log at "), result.content());
        h.assertTrue(result.content().contains("dark_oak_log at "), result.content());
        h.assertTrue(
                result.content().indexOf("mangrove_log") < result.content().indexOf("dark_oak_log"), result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void findTreesAroundTheSpeaker(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(13, y, 13), Blocks.MANGROVE_LOG);
        }
        leaf(h, 13, 6, 13);
        for (int y = 2; y <= 4; y++) {
            h.setBlock(new BlockPos(2, y, 2), Blocks.DARK_OAK_LOG);
        }
        leaf(h, 2, 5, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 12.5, 2, 12.5);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        c.setOwner(owner.getUUID());
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(13, 2, 13))),
                "speaker's tree should be directly visible");
        BlockPos mangrove = h.absolutePos(new BlockPos(13, 2, 13));
        ToolResult result = run(
                tools(c),
                new Caller(owner.getUUID(), "owner", AccessLevel.OWNER),
                "find_trees",
                "{\"near\":\"me\",\"radius\":8,\"limit\":1}");
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(
                result.content()
                        .contains("mangrove_log at " + mangrove.getX() + " " + mangrove.getY() + " " + mangrove.getZ()),
                result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void finderDescendsToTheBase(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 10; y++) {
            h.setBlock(new BlockPos(8, y, 8), Blocks.CHERRY_LOG);
        }
        for (int x = 5; x <= 7; x++) {
            for (int z = 5; z <= 9; z++) {
                h.setBlock(new BlockPos(x, 8, z), Blocks.STONE);
            }
        }
        BlockPos origin = h.absolutePos(new BlockPos(7, 9, 8));
        List<TreeFinder.TreeInfo> found =
                ofType(TreeFinder.find(h.getLevel(), origin, 8, 16, 32, null, 10, true), "cherry_log");
        h.assertValueEqual(found.size(), 1, "trees: " + found);
        TreeFinder.TreeInfo tree = found.get(0);
        h.assertTrue(tree.base().equals(h.absolutePos(new BlockPos(8, 2, 8))), "base " + tree.base());
        h.assertValueEqual(tree.logs().size(), 9, "logs " + tree.logs().size());
        h.assertValueEqual(tree.height(), 9, "height " + tree.height());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void classifiesLeftovers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 4; y++) {
            h.setBlock(new BlockPos(3, y, 3), Blocks.BIRCH_LOG); // supported 1x1: stump
        }
        h.setBlock(new BlockPos(6, 5, 3), Blocks.SPRUCE_LOG); // air below: floating
        h.setBlock(new BlockPos(6, 6, 3), Blocks.SPRUCE_LOG);
        for (int x = 9; x <= 11; x++) {
            for (int z = 10; z <= 12; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.PALE_OAK_LOG); // footprint 9: structure
                }
            }
        }
        BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));
        List<TreeFinder.TreeInfo> found = ofType(
                TreeFinder.find(h.getLevel(), origin, 16, 16, 32, null, 20, false),
                "birch_log",
                "spruce_log",
                "pale_oak_log");
        h.assertValueEqual(found.size(), 3, "trees: " + found);
        h.assertTrue(kindOf(found, "birch_log") == TreeFinder.Kind.STUMP, "kinds: " + found);
        h.assertTrue(kindOf(found, "spruce_log") == TreeFinder.Kind.FLOATING, "kinds: " + found);
        h.assertTrue(kindOf(found, "pale_oak_log") == TreeFinder.Kind.LOG_STRUCTURE, "kinds: " + found);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void treesSortBeforeStructures(GameTestHelper h) {
        // Keep the scan volume above terrain trees outside the small test platform.
        GameTestSupport.platformAt(h, 100, 16);
        for (int y = 101; y <= 104; y++) {
            h.setBlock(new BlockPos(12, y, 12), Blocks.JUNGLE_LOG); // tree, farther away
        }
        leaf(h, 11, 104, 12);
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 5; z++) {
                h.setBlock(new BlockPos(x, 101, z), Blocks.ACACIA_LOG); // structure, nearer
            }
        }
        BlockPos origin = h.absolutePos(new BlockPos(2, 101, 2));
        List<TreeFinder.TreeInfo> found =
                ofType(TreeFinder.find(h.getLevel(), origin, 16, 16, 32, null, 20, false), "jungle_log", "acacia_log");
        h.assertValueEqual(found.size(), 2, "trees: " + found);
        h.assertTrue(found.get(0).kind() == TreeFinder.Kind.STANDING_TREE, "first: " + found.get(0));
        h.assertTrue(found.get(1).kind() == TreeFinder.Kind.LOG_STRUCTURE, "last: " + found);
        h.succeed();
    }

    @GameTest(maxTicks = 1600)
    public void acaciaTreeIsFelledCompletely(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        int[][] logs = {{6, 2, 9}, {6, 3, 9}, {7, 3, 9}, {7, 4, 9}, {5, 3, 9}, {5, 4, 9}};
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int[] p : logs) {
            h.setBlock(new BlockPos(p[0], p[1], p[2]), Blocks.ACACIA_LOG);
            cells.add(new BlockPos(p[0], p[1], p[2]));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, cells, "acacia_log", 3),
                        BlockMatcher.parse("minecraft:acacia_log").orElseThrow(),
                        0,
                        true),
                300);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (int[] p : logs) {
                h.assertTrue(
                        h.getBlockState(new BlockPos(p[0], p[1], p[2])).isAir(),
                        "log left at " + p[0] + "," + p[1] + "," + p[2] + "; " + result.summary);
            }
            h.assertValueEqual(c.inventory().countItem(Items.ACACIA_LOG), 6, "acacia drops");
        });
    }

    @GameTest(maxTicks = 120)
    public void findTreesAfterTheObservationTick(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(6, y, 6), Blocks.ACACIA_LOG);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 6))),
                "acacia base should be directly visible");
        var tools = tools(c);
        var anonymous = new Caller(null, "", AccessLevel.TRUSTED);
        h.succeedWhen(() -> {
            ToolResult result = run(tools, anonymous, "find_trees", "{\"near\":\"self\",\"radius\":16}");
            h.assertTrue(!result.error(), result.content());
            h.assertTrue(result.content().contains("acacia_log"), result.content());
        });
    }

    /** A log walled in by stone is not a log behind leaves: the feller must not dig the stone. */
    @GameTest(maxTicks = 1200)
    public void doesNotDigStoneToReachALog(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_LOG);
        h.setBlock(new BlockPos(6, 3, 6), Blocks.STONE);
        h.setBlock(new BlockPos(5, 2, 6), Blocks.STONE);
        h.setBlock(new BlockPos(7, 2, 6), Blocks.STONE);
        h.setBlock(new BlockPos(6, 2, 5), Blocks.STONE);
        h.setBlock(new BlockPos(6, 2, 7), Blocks.STONE);
        // Close enough for the ground fallback to try the breaker; further away it would refuse before digging.
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 6);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        List<BlockPos> cells = List.of(new BlockPos(6, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner =
                start(c, result, new FellTreeTask(info(h, cells, "oak_log", 1), oak(), 0, false), 120);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 6)).is(Blocks.OAK_LOG), "the log must stay");
            h.assertTrue(h.getBlockState(new BlockPos(6, 3, 6)).is(Blocks.STONE), "the stone must stay");
            h.assertTrue(h.getBlockState(new BlockPos(5, 2, 6)).is(Blocks.STONE), "the stone must stay");
        });
    }

    /** The trunk of the test tree is wrapped in persistent leaves; every log must still come down. */
    @GameTest(maxTicks = 3000)
    public void leafCoveredLogIsUncoveredAndCut(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 5; y++) {
            BlockPos p = new BlockPos(6, y, 6);
            h.setBlock(p, Blocks.SPRUCE_LOG);
            logs.add(p);
        }
        for (int y = 3; y <= 5; y++) {
            leaf(h, 5, y, 6);
            leaf(h, 7, y, 6);
            leaf(h, 6, y, 5);
            leaf(h, 6, y, 7);
        }
        leaf(h, 6, 6, 6);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, logs, "spruce_log", 4),
                        BlockMatcher.parse("minecraft:spruce_log").orElseThrow(),
                        0,
                        true),
                300);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : logs) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p + "; " + result.summary);
            }
        });
    }

    /** The first tree is too short for the amount asked; the companion must fell a second one to top the count up. */
    @GameTest(maxTicks = 6000)
    public void fellLogsTopsUpAcrossTrees(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> first = new java.util.ArrayList<>();
        for (int y = 2; y <= 3; y++) {
            BlockPos p = new BlockPos(5, y, 5);
            h.setBlock(p, Blocks.SPRUCE_LOG);
            first.add(p);
        }
        leaf(h, 5, 4, 5);
        List<BlockPos> second = new java.util.ArrayList<>();
        for (int y = 2; y <= 3; y++) {
            BlockPos p = new BlockPos(11, y, 4);
            h.setBlock(p, Blocks.SPRUCE_LOG);
            second.add(p);
        }
        leaf(h, 11, 4, 4);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(5, 2, 5))), "first tree visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(11, 2, 4))), "second tree visible");
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        FellLogsTask task =
                new FellLogsTask(BlockMatcher.parse("minecraft:spruce_log").orElseThrow(), 3, 32, true);
        runner.start(task, "obtain_logs");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : first) {
                h.assertTrue(h.getBlockState(p).isAir(), "first tree log left at " + p);
            }
            for (BlockPos p : second) {
                h.assertTrue(h.getBlockState(p).isAir(), "second tree log left at " + p);
            }
            h.assertTrue(c.inventory().countItem(Items.SPRUCE_LOG) >= 3, "gained logs");
            h.assertValueEqual(
                    task.workResult().orElseThrow().lines(),
                    List.of(new TaskWorkResult.Line(
                            "minecraft:spruce_log",
                            3,
                            c.inventory().countItem(Items.SPRUCE_LOG),
                            TaskWorkResult.Evidence.CARRIED)),
                    "verified felling work");
        });
    }

    @GameTest(maxTicks = 1800)
    public void prefersAReachableLowTreeForLumber(GameTestHelper h) {
        int surfaceY = 60;
        GameTestSupport.platformAt(h, surfaceY, 20);
        List<BlockPos> costly = new java.util.ArrayList<>();
        for (int y = surfaceY + 1; y <= surfaceY + 14; y++) {
            BlockPos log = new BlockPos(5, y, 5);
            h.setBlock(log, Blocks.SPRUCE_LOG);
            costly.add(log);
        }
        leaf(h, 5, surfaceY + 15, 5);
        List<BlockPos> reachable = new java.util.ArrayList<>();
        for (int y = surfaceY + 1; y <= surfaceY + 5; y++) {
            BlockPos log = new BlockPos(13, y, 5);
            h.setBlock(log, Blocks.SPRUCE_LOG);
            reachable.add(log);
        }
        leaf(h, 13, surfaceY + 6, 5);
        List<BlockPos> otherSpecies = new java.util.ArrayList<>();
        for (int y = surfaceY + 1; y <= surfaceY + 4; y++) {
            BlockPos log = new BlockPos(9, y, 12);
            h.setBlock(log, Blocks.OAK_LOG);
            otherSpecies.add(log);
        }
        leaf(h, 9, surfaceY + 5, 12);

        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, surfaceY + 1, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        for (BlockPos log : costly) {
            observeFromBeside(h, c, log);
        }
        for (BlockPos log : reachable) {
            observeFromBeside(h, c, log);
        }
        for (BlockPos log : otherSpecies) {
            observeFromBeside(h, c, log);
        }
        observeFromBeside(h, c, new BlockPos(5, surfaceY + 15, 5));
        observeFromBeside(h, c, new BlockPos(13, surfaceY + 6, 5));
        observeFromBeside(h, c, new BlockPos(9, surfaceY + 5, 12));
        var start = h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, surfaceY + 1, 2.5));
        c.teleportTo(start.x, start.y, start.z);

        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        runner.start(
                new FellLogsTask(BlockMatcher.parse("minecraft:spruce_log").orElseThrow(), 5, 32, true), "obtain_logs");
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("from 1 tree"),
                    "the estimated climb cost should select the ground-reachable tree: " + result.summary);
            h.assertValueEqual(
                    c.inventory().countItem(Items.SPRUCE_LOG), 5, "only the requested reachable logs were collected");
            for (BlockPos log : costly) {
                h.assertTrue(h.getBlockState(log).is(Blocks.SPRUCE_LOG), "the costly near tree was cut at " + log);
            }
            for (BlockPos log : reachable) {
                h.assertTrue(h.getBlockState(log).isAir(), "the selected low tree remains at " + log);
            }
            for (BlockPos log : otherSpecies) {
                h.assertTrue(h.getBlockState(log).is(Blocks.OAK_LOG), "the explicit spruce target spared " + log);
            }
        });
    }

    @GameTest(maxTicks = 3600)
    public void gathersPartialTreeThenUsesAnotherTree(GameTestHelper h) {
        int surfaceY = 60;
        GameTestSupport.platformAt(h, surfaceY, 20);
        List<BlockPos> first = new java.util.ArrayList<>();
        for (int y = surfaceY + 1; y <= surfaceY + 6; y++) {
            BlockPos log = new BlockPos(5, y, 5);
            h.setBlock(log, Blocks.SPRUCE_LOG);
            first.add(log);
        }
        leaf(h, 5, surfaceY + 7, 5);
        List<BlockPos> second = new java.util.ArrayList<>();
        for (int y = surfaceY + 1; y <= surfaceY + 3; y++) {
            BlockPos log = new BlockPos(12, y, 5);
            h.setBlock(log, Blocks.SPRUCE_LOG);
            second.add(log);
        }
        leaf(h, 12, surfaceY + 4, 5);
        List<BlockPos> structure = new java.util.ArrayList<>();
        for (int x = 8; x <= 12; x++) {
            BlockPos log = new BlockPos(x, surfaceY + 1, 12);
            h.setBlock(log, Blocks.SPRUCE_LOG);
            structure.add(log);
        }

        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, surfaceY + 1, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        c.inventory().addItem(new ItemStack(Items.DIRT, 8));
        Set<BlockPos> protectedLogs = first.subList(0, 3).stream()
                .map(h::absolutePos)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        c.hands().overrideProtection(protectedLogs::contains);
        for (BlockPos log : first) observeFromBeside(h, c, log);
        for (BlockPos log : second) observeFromBeside(h, c, log);
        for (BlockPos log : structure) observeFromBeside(h, c, log);
        observeFromBeside(h, c, new BlockPos(5, surfaceY + 7, 5));
        observeFromBeside(h, c, new BlockPos(12, surfaceY + 4, 5));
        var start = h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, surfaceY + 1, 2.5));
        c.teleportTo(start.x, start.y, start.z);

        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        runner.start(
                new FellLogsTask(BlockMatcher.parse("minecraft:spruce_log").orElseThrow(), 6, 32, true), "obtain_logs");
        boolean[] firstBeforeSecond = {false};
        boolean[] secondAfterFirst = {false};
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            boolean firstCut = first.subList(3, first.size()).stream()
                    .allMatch(pos -> h.getBlockState(pos).isAir());
            boolean protectedStillPresent = first.subList(0, 3).stream()
                    .allMatch(pos -> h.getBlockState(pos).is(Blocks.SPRUCE_LOG));
            boolean secondPresent =
                    second.stream().allMatch(pos -> h.getBlockState(pos).is(Blocks.SPRUCE_LOG));
            if (firstCut && protectedStillPresent && secondPresent) firstBeforeSecond[0] = true;
            if (firstBeforeSecond[0]
                    && second.stream().anyMatch(pos -> h.getBlockState(pos).isAir())) {
                secondAfterFirst[0] = true;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(firstBeforeSecond[0] && secondAfterFirst[0], "trees were processed in order");
            h.assertValueEqual(c.inventory().countItem(Items.SPRUCE_LOG), 6, "only carried logs count");
            for (BlockPos log : first.subList(0, 3)) {
                h.assertTrue(h.getBlockState(log).is(Blocks.SPRUCE_LOG), "protected log was left standing at " + log);
            }
            for (BlockPos log : second) {
                h.assertTrue(h.getBlockState(log).isAir(), "alternate tree log left at " + log);
            }
            for (BlockPos log : structure) {
                h.assertTrue(h.getBlockState(log).is(Blocks.SPRUCE_LOG), "structural wood was cut at " + log);
            }
        });
    }

    /** A row of loose logs is no tree: the search gives up and the count miner gathers them, and the summary says so. */
    @GameTest(maxTicks = 1200)
    public void fellLogsMinesLooseLogsWhenNoTreeStands(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> loose = new java.util.ArrayList<>();
        for (int x = 5; x <= 9; x++) {
            BlockPos p = new BlockPos(x, 2, 8);
            h.setBlock(p, Blocks.SPRUCE_LOG);
            loose.add(p);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        for (BlockPos p : loose) {
            h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(p)), "loose log should be visible at " + p);
        }
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(result);
        runner.start(
                new FellLogsTask(BlockMatcher.parse("minecraft:spruce_log").orElseThrow(), 3, 32, true), "obtain_logs");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("0 felled"), "a log row is not a felled tree: " + result.summary);
            h.assertTrue(result.summary.contains("mined"), "the fallback must be named: " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.SPRUCE_LOG) >= 3, "gained logs");
        });
    }

    /** A tall jungle tree with side branches: every log, branch included, must come down. */
    @GameTest(maxTicks = 6000)
    public void wideBranchedTreeFallsCompletely(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 12; y++) {
            h.setBlock(new BlockPos(8, y, 8), Blocks.JUNGLE_LOG);
            logs.add(new BlockPos(8, y, 8));
        }
        int[][] branches = {{6, 8, 8}, {5, 8, 8}, {10, 9, 8}, {11, 9, 8}};
        for (int[] p : branches) {
            h.setBlock(new BlockPos(p[0], p[1], p[2]), Blocks.JUNGLE_LOG);
            logs.add(new BlockPos(p[0], p[1], p[2]));
        }
        for (int x = 4; x <= 12; x++) {
            for (int z = 5; z <= 11; z++) {
                for (int y = 9; y <= 13; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (h.getBlockState(p).isAir()) {
                        h.setBlock(p, Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                    }
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 32));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, logs, "jungle_log", 11),
                        BlockMatcher.parse("minecraft:jungle_log").orElseThrow(),
                        0,
                        true),
                400);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : logs) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p + "; " + result.summary);
            }
        });
    }

    private static TreeFinder.TreeInfo info(GameTestHelper h, List<BlockPos> logs, String type, int height) {
        List<BlockPos> absolute = logs.stream().map(h::absolutePos).toList();
        return new TreeFinder.TreeInfo(
                type, absolute, absolute.get(0), height, TreeFinder.Kind.STANDING_TREE, 8.0, "south-east");
    }

    @GameTest(maxTicks = 5000)
    public void branchingJungleGiantFallsFromTheCrown(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 17; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.JUNGLE_LOG);
            logs.add(new BlockPos(6, y, 6));
        }
        h.setBlock(new BlockPos(5, 14, 6), Blocks.JUNGLE_LOG);
        logs.add(new BlockPos(5, 14, 6));
        h.setBlock(new BlockPos(7, 13, 6), Blocks.JUNGLE_LOG);
        logs.add(new BlockPos(7, 13, 6));
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                for (int y = 15; y <= 18; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (h.getBlockState(p).isAir()) {
                        h.setBlock(p, Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                    }
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 32));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, logs, "jungle_log", 16),
                        BlockMatcher.parse("minecraft:jungle_log").orElseThrow(),
                        0,
                        true),
                300);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : logs) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p + "; summary " + result.summary);
            }
            h.assertValueEqual(c.inventory().countItem(Items.JUNGLE_LOG), logs.size(), "drops");
            h.assertValueEqual(dirtLeft(h), 0, "scaffold left in the world");
        });
    }

    @GameTest(maxTicks = 400)
    public void refusesATreeTallerThanItsScaffold(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 40; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
            logs.add(new BlockPos(6, y, 6));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 64));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 60, () -> false);
        runner.setEventSink(result);
        runner.start(new FellTreeTask(info(h, logs, "oak_log", 39), oak(), 0, false), "fell_tree");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("more than the 32"), "summary " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 40, 6)).is(Blocks.OAK_LOG), "nothing may be cut");
            h.assertValueEqual(dirtLeft(h), 0, "a refused climb must not place a block");
        });
    }

    @GameTest(maxTicks = 400)
    public void refusesWithoutScaffoldMaterial(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 20; y++) {
            h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
            logs.add(new BlockPos(6, y, 6));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 60, () -> false);
        runner.setEventSink(result);
        runner.start(new FellTreeTask(info(h, logs, "oak_log", 19), oak(), 0, false), "fell_tree");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("dirt or cobblestone"), "summary " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 20, 6)).is(Blocks.OAK_LOG), "nothing may be cut");
            h.assertValueEqual(dirtLeft(h), 0, "an empty bag must not place a block");
        });
    }

    @GameTest(maxTicks = 6000)
    public void cancelledFellingTakesItsScaffoldDown(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 13; y++) {
            for (int x = 8; x <= 9; x++) {
                for (int z = 8; z <= 9; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    h.setBlock(p, Blocks.DARK_OAK_LOG);
                    logs.add(p);
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 64));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, logs, "dark_oak_log", 12),
                        BlockMatcher.parse("minecraft:dark_oak_log").orElseThrow(),
                        0,
                        false),
                300);
        boolean[] cancelled = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!cancelled[0] && dirtLeft(h) >= 3) {
                runner.stop("test cancel");
                cancelled[0] = true;
            }
            if (!cancelled[0]) {
                h.assertTrue(false, "waiting for the climb to start");
            }
            h.assertValueEqual(dirtLeft(h), 0, "a cancelled felling left its column in the world");
        });
    }

    /**
     * The owner pointed at the crown: the sight cache holds the top logs only, and the expansion must still reach the
     * foot, or the companion would cut the crown and leave a 22-block stump.
     */
    @GameTest(maxTicks = 12000)
    public void crownSeedStillFellsTheWholeTrunk(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 25; y++) {
            for (int x = 8; x <= 9; x++) {
                for (int z = 8; z <= 9; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    h.setBlock(p, Blocks.DARK_OAK_LOG);
                    logs.add(p);
                }
            }
        }
        for (int x = 6; x <= 11; x++) {
            for (int z = 6; z <= 11; z++) {
                BlockPos p = new BlockPos(x, 26, z);
                if (h.getBlockState(p).isAir()) {
                    h.setBlock(p, Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
            }
        }
        java.util.Map<BlockPos, String> observed = new java.util.LinkedHashMap<>();
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(Blocks.DARK_OAK_LOG)
                .toString();
        for (BlockPos p : logs) {
            if (p.getY() >= 24) {
                observed.put(h.absolutePos(p), id);
            }
        }
        java.util.Set<BlockPos> observedLeaves = java.util.Set.of(h.absolutePos(new BlockPos(8, 26, 8)));
        List<TreeFinder.TreeInfo> found = TreeFinder.findObserved(
                h.getLevel(), h.absolutePos(new BlockPos(8, 2, 8)), observed, observedLeaves, 4, true);
        h.assertValueEqual(found.size(), 1, "one tree from the crown seed: " + found);
        TreeFinder.TreeInfo tree = found.get(0);
        h.assertValueEqual(tree.logs().size(), 96, "the expansion reached the unseen foot");
        h.assertTrue(
                tree.base().equals(h.absolutePos(new BlockPos(8, 2, 8))),
                "the base is the foot of the trunk: " + tree.base());
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 64));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        tree, BlockMatcher.parse("minecraft:dark_oak_log").orElseThrow(), 0, true),
                400);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : logs) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p + "; " + result.summary);
            }
            h.assertTrue(result.summary.contains("96 of 96"), "summary " + result.summary);
        });
    }

    /**
     * A log on a canopy leaf is out of every walkable reach; the felling must break the leaf it rests on so the item
     * falls where the sweep can take it. The leaf sits beside a small tree, inside the felling's reach from the ground.
     */
    @GameTest(maxTicks = 1200)
    public void theLeafUnderAStrandedDropIsBroken(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> cells = strandedTree(h);
        h.spawnItem(Items.OAK_LOG, 6.5f, 6.0f, 8.5f);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        FellTreeTask task = new FellTreeTask(info(h, cells, "oak_log", 4), oak(), 0, true);
        ExecutionCoordinator runner = start(c, result, task);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            for (BlockPos p : cells) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p + "; " + result.summary);
            }
            h.assertTrue(
                    task.rescued() >= 1,
                    "the leaf under the stranded drop was never broken; " + result.summary + "; " + items(h, 2)
                            + "; companion " + c.blockPosition());
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 5, 8)).isAir(), "the support leaf must be gone: " + result.summary);
        });
    }

    /** A small oak and the canopy leaf beside it that holds the stranded drop. */
    private static List<BlockPos> strandedTree(GameTestHelper h) {
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(8, y, 8), Blocks.OAK_LOG);
            cells.add(new BlockPos(8, y, 8));
        }
        leaf(h, 7, 6, 8);
        leaf(h, 6, 5, 8);
        return cells;
    }

    /** An item the owner dropped at the foot is not this felling's product and must survive. */
    @GameTest(maxTicks = 1200)
    public void aForeignItemAtTheFootIsLeftAlone(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> cells = strandedTree(h);
        h.spawnItem(Items.DIAMOND, 6.5f, 2.0f, 8.5f);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        FellTreeTask task = new FellTreeTask(info(h, cells, "oak_log", 4), oak(), 0, true);
        ExecutionCoordinator runner = start(c, result, task);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertFalse(
                    h.getLevel()
                            .getEntitiesOfClass(
                                    ItemEntity.class, new AABB(h.absolutePos(new BlockPos(6, 1, 8))).inflate(3))
                            .isEmpty(),
                    "the owner's diamond was taken: " + result.summary);
        });
    }

    /** A protected leaf must stop the rescue: no break, no retry, and the drop stays where it is. */
    @GameTest(maxTicks = 1200)
    public void aProtectedLeafStopsTheRescue(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> cells = strandedTree(h);
        h.spawnItem(Items.OAK_LOG, 6.5f, 6.0f, 8.5f);
        BlockPos protectedLeaf = h.absolutePos(new BlockPos(6, 5, 8));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hands().overrideProtection(p -> p.equals(protectedLeaf));
        Result result = new Result();
        FellTreeTask task = new FellTreeTask(info(h, cells, "oak_log", 4), oak(), 0, true);
        ExecutionCoordinator runner = start(c, result, task);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertValueEqual(task.rescued(), 0, "a protected leaf must not be broken: " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 5, 8)).is(Blocks.OAK_LEAVES),
                    "a protected leaf must stay: " + result.summary);
        });
    }

    /**
     * The stance comes from the plan, one per layer: two logs of the same layer are cut without moving, and every
     * break happens from an interaction reach.
     */
    @GameTest(maxTicks = 4000)
    public void aLayerIsCutFromOneStance(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 7; y++) {
            for (int x = 8; x <= 9; x++) {
                for (int z = 8; z <= 9; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    h.setBlock(p, Blocks.CRIMSON_STEM);
                    logs.add(p);
                }
            }
        }
        for (int x = 6; x <= 11; x++) {
            for (int z = 6; z <= 11; z++) {
                BlockPos p = new BlockPos(x, 8, z);
                if (h.getBlockState(p).isAir()) {
                    h.setBlock(p, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Result result = new Result();
        ExecutionCoordinator runner = start(
                c,
                result,
                new FellTreeTask(
                        info(h, logs, "crimson_stem", 6),
                        BlockMatcher.parse("minecraft:crimson_stem").orElseThrow(),
                        0,
                        false),
                300);
        BlockPos[] lastStance = {null};
        int[] lastLayer = {Integer.MIN_VALUE};
        java.util.Set<BlockPos> gone = new java.util.HashSet<>();
        // Measurements of the three reports: the order, the stance per break and the distance moved between breaks.
        StringBuilder trace = new StringBuilder();
        h.succeedWhen(() -> {
            BlockPos before = c.blockPosition();
            runner.tick();
            for (BlockPos p : logs) {
                if (!gone.contains(p) && h.getBlockState(p).isAir()) {
                    gone.add(p);
                    double eyeDistance =
                            c.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(p)));
                    trace.append(" log ")
                            .append(p)
                            .append(" from ")
                            .append(before)
                            .append(" eye ")
                            .append(String.format(java.util.Locale.ROOT, "%.2f", eyeDistance))
                            .append(';');
                    h.assertTrue(
                            eyeDistance <= dev.friendai.world.movement.Approach.REACH,
                            "break out of reach: " + p + " eye " + eyeDistance);
                    if (lastStance[0] != null && lastLayer[0] == p.getY()) {
                        double moved = Math.sqrt(net.minecraft.world.phys.Vec3.atCenterOf(lastStance[0])
                                .distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(before)));
                        h.assertTrue(moved < 1.0, "stepped " + moved + " between two logs of one layer:" + trace);
                    }
                    lastStance[0] = before;
                    lastLayer[0] = p.getY();
                }
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(trace.length() > 0, "no break observed");
        });
    }

    /** Every item entity near one arena, for a failure message: what is lying where, and whether it settled. */
    private static String items(GameTestHelper h, int baseY) {
        BlockPos centre = h.absolutePos(new BlockPos(8, baseY, 8));
        StringBuilder out = new StringBuilder("items[");
        for (var drop : h.getLevel()
                .getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(centre).inflate(10))) {
            out.append(drop.getItem().getItem())
                    .append(' ')
                    .append(String.format(
                            java.util.Locale.ROOT, "%.2f %.2f %.2f", drop.getX(), drop.getY(), drop.getZ()))
                    .append(" ground=")
                    .append(drop.onGround())
                    .append(" age=")
                    .append(drop.getAge())
                    .append(" below=")
                    .append(h.getLevel()
                            .getBlockState(drop.blockPosition().below())
                            .getBlock())
                    .append("; ");
        }
        return out.append(']').toString();
    }
}
