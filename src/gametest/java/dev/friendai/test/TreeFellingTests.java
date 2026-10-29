package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.minecraft.task.FellTreeTask;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

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
            h.assertTrue(h.getBlockState(new BlockPos(6, 6, 6)).isAir(), "the top log must be cut");
            h.assertTrue(h.getBlockState(new BlockPos(6, 5, 6)).isAir(), "the second log must be cut");
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 6)).is(Blocks.OAK_LOG), "the rest must stay");
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
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("protected"), "summary " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(6, 5, 6)).is(Blocks.OAK_LOG), "the protected log must stay");
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 6)).isAir(), "the reachable logs must be cut");
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
}
