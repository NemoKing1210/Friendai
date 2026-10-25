package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.task.mining.TreeFinder;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/**
 * Tree recognition and felling. The world is shared and tests run in parallel, so each test uses its own wood types
 * and looks only at the trees of those types.
 */
public class TreeFellingTests {
    /** Persistent leaves, so they do not decay while the test waits. */
    private static void leaf(GameTestHelper h, int x, int y, int z) {
        h.setBlock(new BlockPos(x, y, z),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
    }

    private static List<TreeFinder.TreeInfo> ofType(List<TreeFinder.TreeInfo> found, String... types) {
        List<String> wanted = List.of(types);
        return found.stream().filter(t -> wanted.contains(t.type())).toList();
    }

    private static TreeFinder.Kind kindOf(List<TreeFinder.TreeInfo> found, String type) {
        return found.stream().filter(t -> t.type().equals(type)).findFirst().orElseThrow().kind();
    }

    private static ToolResult run(ToolExecutor tools, Caller caller, String name, String json) {
        return tools.execute(
                new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null), caller);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult result = run(FriendaiTools.create(() -> 32).bind(c), new Caller(null, "", AccessLevel.TRUSTED),
                "find_trees", "{\"near\":\"self\",\"radius\":16}");
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("mangrove_log at "), result.content());
        h.assertTrue(result.content().contains("dark_oak_log at "), result.content());
        h.assertTrue(result.content().indexOf("mangrove_log") < result.content().indexOf("dark_oak_log"),
                result.content());
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
        BlockPos mangrove = h.absolutePos(new BlockPos(13, 2, 13));
        ToolResult result = run(FriendaiTools.create(() -> 32).bind(c),
                new Caller(owner.getUUID(), "owner", AccessLevel.OWNER),
                "find_trees", "{\"near\":\"me\",\"radius\":8,\"limit\":1}");
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("mangrove_log at "
                + mangrove.getX() + " " + mangrove.getY() + " " + mangrove.getZ()), result.content());
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
                "birch_log", "spruce_log", "pale_oak_log");
        h.assertValueEqual(found.size(), 3, "trees: " + found);
        h.assertTrue(kindOf(found, "birch_log") == TreeFinder.Kind.STUMP, "kinds: " + found);
        h.assertTrue(kindOf(found, "spruce_log") == TreeFinder.Kind.FLOATING, "kinds: " + found);
        h.assertTrue(kindOf(found, "pale_oak_log") == TreeFinder.Kind.LOG_STRUCTURE, "kinds: " + found);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void treesSortBeforeStructures(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(12, y, 12), Blocks.JUNGLE_LOG); // tree, farther away
        }
        leaf(h, 11, 5, 12);
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 5; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.ACACIA_LOG); // structure, nearer
            }
        }
        BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));
        List<TreeFinder.TreeInfo> found = ofType(
                TreeFinder.find(h.getLevel(), origin, 16, 16, 32, null, 20, false), "jungle_log", "acacia_log");
        h.assertValueEqual(found.size(), 2, "trees: " + found);
        h.assertTrue(found.get(0).kind() == TreeFinder.Kind.STANDING_TREE, "first: " + found.get(0));
        h.assertTrue(found.get(1).kind() == TreeFinder.Kind.LOG_STRUCTURE, "last: " + found);
        h.succeed();
    }
}
