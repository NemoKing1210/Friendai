package dev.friedai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import dev.friedai.tool.builtin.FriedaiTools;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class ToolTests {
    private static ToolResult run(ToolExecutor tools, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null));
    }

    private static ToolExecutor tools(CompanionEntity c) {
        return FriedaiTools.create(() -> 32).bind(c);
    }

    @GameTest
    public void mineToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_blocks", "{\"block\":\"oak_log\",\"count\":3}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(c.taskRunner().current().get().type().equals("mine_blocks"), "mine task should be current");
        h.succeed();
    }

    @GameTest
    public void mineToolRejectsUnknownBlock(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_blocks", "{\"block\":\"nonsense:block\",\"count\":3}");
        h.assertTrue(r.error() && r.content().equals("unknown block or tag: nonsense:block"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void goToNeedsTarget(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "go_to", "{}");
        h.assertTrue(r.error() && r.content().equals("need x,y,z or target=owner"), "result was " + r.content());
        ToolResult ok = run(tools(c), "go_to", "{\"x\":5,\"y\":64,\"z\":5}");
        h.assertTrue(!ok.error() && ok.content().startsWith("task #"), "result was " + ok.content());
        h.succeed();
    }

    @GameTest
    public void stopReturnsToFollow(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        run(tools, "stay", "{}");
        h.assertTrue(c.taskRunner().current().get().type().equals("stay"), "should be staying");
        ToolResult r = run(tools, "stop", "{}");
        h.assertTrue(!r.error() && r.content().equals("stopped"), "result was " + r.content());
        h.assertTrue(c.taskRunner().current().get().type().equals("follow"), "should be following again");
        h.succeed();
    }

    @GameTest
    public void lookAroundListsNearbyLogs(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int i = 0; i < 4; i++) {
            h.setBlock(4 + i, 2, 3, Blocks.OAK_LOG);
        }
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "look_around", "{}");
        h.assertTrue(r.content().contains("oak_log"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void getInventoryListsItems(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        h.assertTrue(run(tools, "get_inventory", "{}").content().equals("Inventory is empty"), "should start empty");
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 5));
        ToolResult r = run(tools, "get_inventory", "{}");
        h.assertTrue(r.content().equals("Inventory: oak_log×5"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void invalidArgumentsNeverStartTasks(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_blocks", "{\"block\":\"oak_log\",\"count\":500}");
        h.assertTrue(r.error(), "count over the maximum must be rejected");
        h.succeed();
    }

    @GameTest
    public void registryExposesToolsInGroupOrder(GameTestHelper h) {
        List<String> names = FriedaiTools.create(() -> 32).specs().stream().map(ToolSpec::name).toList();
        h.assertTrue(names.equals(List.of("follow_player", "stay", "go_to", "mine_blocks", "mine_block_at", "pickup_items",
                "give_items", "drop_items", "get_inventory", "look_around", "find_blocks", "stop")), "names were " + names);
        h.succeed();
    }

    @GameTest
    public void mineAtOutsideHeightIsRejected(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_block_at", "{\"x\":0,\"y\":100000,\"z\":0}");
        h.assertTrue(r.error() && r.content().startsWith("y is outside the world"), "result was " + r.content());
        h.succeed();
    }

    private static String coords(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    @GameTest
    public void findBlocksListsNearestFirst(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(9, 2, 2, Blocks.IRON_BLOCK);
        h.setBlock(5, 2, 2, Blocks.IRON_BLOCK);
        h.setBlock(12, 2, 2, Blocks.IRON_BLOCK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "find_blocks", "{\"block\":\"minecraft:iron_block\",\"limit\":2}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(r.content().startsWith("2 x minecraft:iron_block nearest first: "), "content was " + r.content());
        String nearest = coords(h.absolutePos(new BlockPos(5, 2, 2))) + " (";
        String second = coords(h.absolutePos(new BlockPos(9, 2, 2))) + " (";
        String cut = coords(h.absolutePos(new BlockPos(12, 2, 2))) + " (";
        int nearestAt = r.content().indexOf(nearest);
        int secondAt = r.content().indexOf(second);
        h.assertTrue(nearestAt >= 0 && secondAt > nearestAt, "order was " + r.content());
        h.assertTrue(!r.content().contains(cut), "the far block must be cut by the limit: " + r.content());
        h.succeed();
    }

    @GameTest
    public void findBlocksNone(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "find_blocks", "{\"block\":\"minecraft:sponge\",\"radius\":8}");
        h.assertTrue(!r.error() && r.content().equals("no minecraft:sponge within 8 blocks"), "content was " + r.content());
        h.succeed();
    }
}
