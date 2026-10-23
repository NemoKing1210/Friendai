package dev.friedai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import dev.friedai.tool.builtin.FriedaiTools;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import java.util.UUID;

public class ToolTests {
    private static ToolResult run(ToolExecutor tools, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null));
    }

    private static ToolExecutor tools(CompanionEntity c) {
        return FriedaiTools.create(() -> 32).bind(c);
    }

    /** A mock player with a unique name, because the stock helper names every player "test-mock-player". */
    @SuppressWarnings("removal")
    private static ServerPlayer mockPlayerNamed(GameTestHelper h, String name, double x, double y, double z) {
        var server = h.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(x, y, z));
        player.teleportTo(pos.x, pos.y, pos.z);
        return player;
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
    public void craftToolRejectsUnknownItem(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "craft_item", "{\"item\":\"nonsense:x\",\"count\":1}");
        h.assertTrue(r.error() && r.content().equals("unknown item: nonsense:x"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void craftToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_LOG));
        ToolResult r = run(tools(c), "craft_item", "{\"item\":\"oak_planks\",\"count\":4}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(c.taskRunner().current().get().type().equals("craft_item"), "craft task should be current");
        h.succeed();
    }

    @GameTest
    public void smeltToolRejectsNonFuel(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "smelt_items",
                "{\"item\":\"minecraft:raw_iron\",\"count\":1,\"fuel\":\"minecraft:dirt\"}");
        h.assertTrue(r.error() && r.content().equals("minecraft:dirt is not a fuel"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void smeltToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_IRON));
        ToolResult r = run(tools(c), "smelt_items", "{\"item\":\"minecraft:raw_iron\",\"count\":1}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(c.taskRunner().current().get().type().equals("smelt_items"), "smelt task should be current");
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
        h.assertTrue(run(tools, "get_inventory", "{}").content()
                        .equals("Inventory is empty\nWearing: nothing; holding: nothing; combat mode: defensive"),
                "should start empty");
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 5));
        ToolResult r = run(tools, "get_inventory", "{}");
        h.assertTrue(r.content().equals("Inventory: oak_log×5\nWearing: nothing; holding: nothing; combat mode: defensive"),
                "result was " + r.content());
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
    public void obtainToolRejectsUnknown(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "obtain_item", "{\"item\":\"nonsense:x\",\"count\":1}");
        h.assertTrue(r.error() && r.content().equals("unknown item: nonsense:x"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void registryExposesToolsInGroupOrder(GameTestHelper h) {
        List<String> names = FriedaiTools.create(() -> 32).specs().stream().map(ToolSpec::name).toList();
        h.assertTrue(names.equals(List.of("follow_player", "stay", "go_to", "mine_blocks", "mine_block_at", "pickup_items",
                "give_items", "drop_items", "get_inventory", "place_block", "use_block", "light_area", "store_items",
                "take_items", "check_container", "craft_item", "get_recipe", "smelt_items", "cook_food",
                "collect_smelted", "attack", "attack_player", "set_combat_mode", "equip_item", "eat",
                "farm", "tend_farm", "use_bonemeal", "breed_animals", "shear", "milk", "tame_animal",
                "lead_animals", "harvest_honey", "fish", "obtain_item", "plan_obtain",
                "look_around", "find_blocks", "stop", "run_plan")), "names were " + names);
        h.succeed();
    }

    @GameTest
    public void guestSeesOnlyLookAround(GameTestHelper h) {
        List<String> names =
                FriedaiTools.create(() -> 32).specs(dev.friedai.tool.AccessLevel.GUEST).stream()
                        .map(ToolSpec::name)
                        .toList();
        h.assertTrue(names.equals(List.of("look_around")), "names were " + names);
        h.succeed();
    }

    @GameTest
    public void runPlanAcceptsNewTools(GameTestHelper h) {
        ToolSpec spec = FriedaiTools.create(() -> 32).specs().stream()
                .filter(s -> s.name().equals("run_plan"))
                .findFirst()
                .orElseThrow();
        JsonArray allowed = spec.parametersSchema().getAsJsonObject("properties").getAsJsonObject("steps")
                .getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("tool").getAsJsonArray("enum");
        List<String> names = new java.util.ArrayList<>();
        allowed.forEach(entry -> names.add(entry.getAsString()));
        for (String tool : List.of("place_block", "use_block", "light_area", "store_items", "take_items", "check_container")) {
            h.assertTrue(names.contains(tool), tool + " missing from " + names);
        }
        h.succeed();
    }

    @GameTest
    public void mineAtOutsideHeightIsRejected(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_block_at", "{\"x\":0,\"y\":100000,\"z\":0}");
        h.assertTrue(r.error() && r.content().startsWith("y is outside the world"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void placeToolRejectsNonBlockAndPartialCoords(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult nonBlock = run(tools(c), "place_block", "{\"block\":\"minecraft:stick\"}");
        h.assertTrue(nonBlock.error() && nonBlock.content().equals("minecraft:stick is not a block"), "result was " + nonBlock.content());
        ToolResult partial = run(tools(c), "place_block", "{\"block\":\"minecraft:stone\",\"x\":1}");
        h.assertTrue(partial.error() && partial.content().equals("give all of x, y, z or none"), "result was " + partial.content());
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

    @GameTest
    public void runPlanRejectsInvalidPlan(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult nonPlannable = run(tools(c), "run_plan", "{\"steps\":[{\"tool\":\"stay\"}]}");
        h.assertTrue(nonPlannable.error()
                && nonPlannable.content().startsWith("Invalid arguments for run_plan: 'steps[1].tool' must be one of "),
                "result was " + nonPlannable.content());
        ToolResult badArgs = run(tools(c), "run_plan",
                "{\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\"}\"}]}");
        h.assertTrue(badArgs.error() && badArgs.content().equals("Invalid plan: step 1: missing required argument 'count'"),
                "result was " + badArgs.content());
        h.succeed();
    }

    @GameTest
    public void runPlanArgsObjectInsteadOfString(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "run_plan",
                "{\"steps\":[{\"tool\":\"drop_items\",\"args\":{\"item\":\"minecraft:dirt\",\"count\":1}}]}");
        h.assertTrue(r.error() && r.content().startsWith("Invalid arguments for run_plan: 'steps[1].args' must be a string"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void runPlanStartsSequence(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DRIPSTONE_BLOCK, 1));
        ToolResult r = run(tools(c), "run_plan",
                "{\"goal\":\"tidy\",\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dripstone_block\\\",\\\"count\\\":1}\"}]}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(c.taskRunner().current().get().type().equals("run_plan"), "the plan should be the current task");
        h.succeed();
    }

    @GameTest
    public void farmToolRejectsStick(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "farm", "{\"crop\":\"minecraft:stick\"}");
        h.assertTrue(r.error() && r.content().equals("minecraft:stick is not something I can plant"),
                "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void breedToolRejectsHorse(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "breed_animals", "{\"target\":\"minecraft:horse\",\"pairs\":1}");
        h.assertTrue(r.error() && r.content().equals("I can't breed minecraft:horse"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void attackToolRefusals(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        ToolResult villager = run(tools, "attack", "{\"target\":\"minecraft:villager\",\"count\":1}");
        h.assertTrue(villager.error() && villager.content().equals("I won't attack minecraft:villager"),
                "result was " + villager.content());
        ToolResult player = run(tools, "attack", "{\"target\":\"minecraft:player\",\"count\":1}");
        h.assertTrue(player.error() && player.content().equals("use attack_player for players"),
                "result was " + player.content());
        ToolResult unknown = run(tools, "attack", "{\"target\":\"nonsense:mob\",\"count\":1}");
        h.assertTrue(unknown.error() && unknown.content().equals("unknown mob: nonsense:mob"),
                "result was " + unknown.content());
        h.succeed();
    }

    @GameTest
    public void setCombatModeTool(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "set_combat_mode", "{\"mode\":\"guard\"}");
        h.assertTrue(!r.error() && r.content().equals("combat mode is now guard"), "result was " + r.content());
        h.assertTrue(c.combatMode() == dev.friedai.task.combat.CombatMode.GUARD, "mode was " + c.combatMode());
        h.succeed();
    }

    @GameTest
    public void attackPlayerRefusesOwnerAndTeam(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = mockPlayerNamed(
                h, "friedai-owner-" + UUID.randomUUID().toString().substring(0, 8), 2, 2, 2);
        ServerPlayer target = mockPlayerNamed(
                h, "friedai-target-" + UUID.randomUUID().toString().substring(0, 8), 5, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        ToolExecutor tools = tools(c);

        ToolResult own = run(tools, "attack_player", "{\"name\":\"" + owner.getName().getString() + "\"}");
        h.assertTrue(own.error() && own.content().equals("I won't attack you"), "result was " + own.content());

        PlayerTeam team = h.getLevel().getScoreboard()
                .addPlayerTeam("friedai_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        team.setAllowFriendlyFire(false);
        h.getLevel().getScoreboard().addPlayerToTeam(owner.getName().getString(), team);
        h.getLevel().getScoreboard().addPlayerToTeam(target.getName().getString(), team);
        ToolResult friendly = run(tools, "attack_player",
                "{\"name\":\"" + target.getName().getString() + "\"}");
        h.assertTrue(friendly.error() && friendly.content().equals("I can't hurt " + target.getName().getString() + " here"),
                "result was " + friendly.content());
        h.getLevel().getScoreboard().removePlayerTeam(team);
        h.succeed();
    }

    @GameTest
    public void attackPlayerIsOwnerOnly(GameTestHelper h) {
        List<String> trusted = FriedaiTools.create(() -> 32).specs(AccessLevel.TRUSTED).stream()
                .map(ToolSpec::name)
                .toList();
        List<String> owner = FriedaiTools.create(() -> 32).specs(AccessLevel.OWNER).stream()
                .map(ToolSpec::name)
                .toList();
        h.assertTrue(!trusted.contains("attack_player"), "trusted tools were " + trusted);
        h.assertTrue(owner.contains("attack_player"), "owner tools were " + owner);
        h.succeed();
    }

}
