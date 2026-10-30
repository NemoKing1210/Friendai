package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.cooking.minecraft.FurnaceJob;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.memory.CompanionName;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.features.survival.minecraft.Food;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.server.runtime.ServerRuntime;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;

public class ToolTests {
    private static ToolResult run(ToolExecutor tools, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null));
    }

    private static ToolExecutor tools(CompanionEntity c) {
        return GameTestToolRegistry.create(c, () -> 32).bind(c);
    }

    private static ToolResult runAs(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null), caller);
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
    public void jumpToolStartsTask(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "jump", "{}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("jump"))
                        .orElse(false),
                "jump task should be current");
        h.succeed();
    }

    @GameTest
    public void mineToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_blocks", "{\"block\":\"oak_log\",\"count\":3}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("mine_blocks"), "mine task should be current");
        h.succeed();
    }

    @GameTest
    public void mineToolRejectsUnknownBlock(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_blocks", "{\"block\":\"nonsense:block\",\"count\":3}");
        h.assertTrue(
                r.error() && r.content().equals("unknown block or tag: nonsense:block"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void craftToolRejectsUnknownItem(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "craft_item", "{\"item\":\"nonsense:x\",\"count\":1}");
        h.assertTrue(r.error() && r.content().equals("unknown item: nonsense:x"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void craftToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_LOG));
        ToolResult r = run(tools(c), "craft_item", "{\"item\":\"oak_planks\",\"count\":4}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("craft_item"), "craft task should be current");
        h.succeed();
    }

    @GameTest
    public void smeltToolRejectsNonFuel(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(
                tools(c), "smelt_items", "{\"item\":\"minecraft:raw_iron\",\"count\":1,\"fuel\":\"minecraft:dirt\"}");
        h.assertTrue(r.error() && r.content().equals("minecraft:dirt is not a fuel"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void smeltToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_IRON));
        ToolResult r = run(tools(c), "smelt_items", "{\"item\":\"minecraft:raw_iron\",\"count\":1}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("smelt_items"), "smelt task should be current");
        h.succeed();
    }

    @GameTest
    public void getFoodToolStartsTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "get_food", "{}");
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("get_food"), "get_food task should be current");
        h.succeed();
    }

    @GameTest
    public void goToNeedsTarget(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "go_to", "{}");
        h.assertTrue(r.error() && r.content().equals("need x,y,z or target=owner"), "result was " + r.content());
        ToolResult ok = run(tools(c), "go_to", "{\"x\":5,\"y\":64,\"z\":5}");
        h.assertTrue(!ok.error() && ok.content().startsWith("task #"), "result was " + ok.content());
        h.succeed();
    }

    @GameTest
    public void stopReturnsToFollow(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        run(tools, "stay", "{}");
        h.assertTrue(c.executionCoordinator().current().get().type().equals("stay"), "should be staying");
        ToolResult r = run(tools, "stop", "{}");
        h.assertTrue(!r.error() && r.content().equals("stopped"), "result was " + r.content());
        h.assertTrue(c.executionCoordinator().current().get().type().equals("follow"), "should be following again");
        h.succeed();
    }

    @GameTest
    public void stopToolScopes(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        run(tools, "stay", "{}");
        ToolResult plain = run(tools, "stop", "{}");
        h.assertTrue(!plain.error() && plain.content().equals("stopped"), "plain stop was " + plain.content());

        run(tools, "stay", "{}");
        ToolResult miss = run(tools, "stop", "{\"what\":\"mine_blocks\"}");
        h.assertTrue(
                miss.error() && miss.content().startsWith("not running: mine_blocks; current task: staying in place"),
                "miss was " + miss.content());
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("stay"),
                "a missed name must leave the task alone");

        c.executionCoordinator().start(new StayTask(), "mine_blocks");
        ToolResult spaced = run(tools, "stop", "{\"what\":\"Mine Blocks\"}");
        h.assertTrue(
                !spaced.error() && spaced.content().equals("stopped staying in place"),
                "named stop was " + spaced.content());
        h.assertTrue(c.executionCoordinator().current().get().type().equals("follow"), "should be following again");

        run(tools, "stay", "{}");
        c.furnaceJobs()
                .add(new FurnaceJob(
                        h.absolutePos(new BlockPos(5, 2, 5)),
                        Station.FURNACE,
                        "minecraft:raw_gold",
                        "minecraft:gold_ingot",
                        1,
                        false));
        ToolResult all = run(tools, "stop", "{\"what\":\"all\"}");
        h.assertTrue(!all.error() && all.content().startsWith("stopped everything: "), "all was " + all.content());
        h.assertTrue(
                all.content().contains("staying in place") && all.content().contains("dropped 1 furnace job"),
                "all was " + all.content());
        h.assertTrue(!all.content().contains("fight"), "no fight was running, all was " + all.content());
        h.assertTrue(c.furnaceJobs().all().isEmpty(), "furnace jobs should be cleared");
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("follow"),
                "should be back on the default activity");
        h.succeed();
    }

    @GameTest
    public void lookAroundListsNearbyLogs(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs =
                List.of(new BlockPos(4, 2, 3), new BlockPos(5, 2, 7), new BlockPos(8, 2, 4), new BlockPos(10, 2, 8));
        logs.forEach(pos -> h.setBlock(pos, Blocks.OAK_LOG));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (BlockPos relative : logs) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(relative)),
                    "fixture log should be visible at " + relative);
        }
        ToolResult r = run(tools(c), "look_around", "{}");
        h.assertTrue(r.content().contains("oak_log"), "result was " + r.content());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void setNameRenamesCompanionAndUpdatesPrompt(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionEntity companion = CompanionManager.get(server).summon(owner);

        ToolResult renamed = runAs(
                tools(companion),
                "set_name",
                "{\"name\":\"  Bob  \"}",
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(
                !renamed.error() && renamed.content().equals("my name is now Bob"), "result was " + renamed.content());
        h.assertTrue(
                companion.getName().getString().equals("Bob"),
                "tag name was " + companion.getName().getString());
        h.assertTrue(companion.isCustomNameVisible(), "the name tag must be visible");
        h.assertTrue(
                state.profiles().profile(owner.getUUID()).name().equals("Bob"),
                "the name should be kept in the owner's profile");

        var prompt = state.sessions().currentPrompt(owner.getUUID());
        h.assertTrue(prompt.isPresent() && prompt.get().contains("You are Bob"), "prompt: " + prompt);

        h.assertTrue(CompanionManager.get(server).dismiss(owner), "the companion should be dismissed");
        CompanionEntity again = CompanionManager.get(server).summon(owner);
        h.assertTrue(again.getName().getString().equals("Bob"), "the name should survive a dismiss");
        h.succeed();
    }

    @GameTest
    public void setNameRejectsBadNamesAndGuests(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionEntity companion = CompanionManager.get(server).summon(owner);
        ToolExecutor tools = tools(companion);

        ToolResult blank = runAs(
                tools,
                "set_name",
                "{\"name\":\"   \"}",
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(
                blank.error() && blank.content().equals("Invalid name. " + CompanionName.PROBLEM),
                "result was " + blank.content());

        ToolResult tooLong = runAs(
                tools,
                "set_name",
                "{\"name\":\"" + "x".repeat(CompanionName.MAX_LENGTH + 1) + "\"}",
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(tooLong.error(), "an over-long name must be rejected");

        ToolResult guest = runAs(
                tools, "set_name", "{\"name\":\"Bob\"}", new Caller(owner.getUUID(), "guest", AccessLevel.TRUSTED));
        h.assertTrue(guest.error() && guest.content().contains("not available"), "result was " + guest.content());
        h.assertTrue(!companion.getName().getString().equals("Bob"), "a guest must not rename the companion");
        h.succeed();
    }

    @GameTest
    public void getInventoryListsItems(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        h.assertTrue(
                run(tools, "get_inventory", "{}")
                        .content()
                        .equals(
                                "Inventory is empty\nWearing: nothing; holding: nothing; combat: bodyguard/balanced; hunger 20/20"),
                "should start empty");
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 5));
        ToolResult r = run(tools, "get_inventory", "{}");
        h.assertTrue(
                r.content()
                        .equals(
                                "Inventory: oak_log×5\nWearing: nothing; holding: nothing; combat: bodyguard/balanced; hunger 20/20"),
                "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void invalidArgumentsNeverStartTasks(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_blocks", "{\"block\":\"oak_log\",\"count\":500}");
        h.assertTrue(r.error(), "count over the maximum must be rejected");
        h.succeed();
    }

    @GameTest
    public void obtainToolRejectsUnknown(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "obtain_item", "{\"item\":\"nonsense:x\",\"count\":1}");
        h.assertTrue(r.error() && r.content().equals("unknown item: nonsense:x"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void registryIncludesFeatureContributions(GameTestHelper h) {
        List<String> names = FriendaiTools.create(() -> 32).specs().stream()
                .map(ToolSpec::name)
                .toList();
        Set<String> expected = Set.of(
                "follow_player",
                "stay",
                "go_to",
                "jump",
                "set_pace",
                "fell_tree",
                "mine_blocks",
                "mine_block_at",
                "pickup_items",
                "give_items",
                "drop_items",
                "get_inventory",
                "place_block",
                "use_block",
                "light_area",
                "clear_area",
                "store_items",
                "take_items",
                "check_container",
                "deposit_items",
                "craft_item",
                "get_recipe",
                "smelt_items",
                "cook_food",
                "collect_smelted",
                "enchant_item",
                "anvil_item",
                "grindstone_item",
                "attack",
                "attack_player",
                "get_combat_settings",
                "set_combat_settings",
                "equip_item",
                "eat",
                "get_food",
                "allow_food",
                "forbid_food",
                "reset_food_rule",
                "get_food_rules",
                "farm",
                "plan_farm",
                "tend_farm",
                "use_bonemeal",
                "breed_animals",
                "shear",
                "milk",
                "tame_animal",
                "lead_animals",
                "harvest_honey",
                "fish",
                "obtain_item",
                "plan_obtain",
                "search_resource",
                "find_target",
                "search_hint",
                "known_resources",
                "remember_place",
                "find_place",
                "go_to_place",
                "rename_place",
                "forget_place",
                "blueprint_help",
                "preview_structure",
                "build_structure",
                "save_blueprint",
                "list_blueprints",
                "build_special",
                "plan_special",
                "find_trees",
                "look_around",
                "find_blocks",
                "find_placed",
                "player_view",
                "describe_block",
                "survey_area",
                "trust_player",
                "untrust_player",
                "show_inventory",
                "set_name",
                "set_follow_mode",
                "stop",
                "find_traders",
                "inspect_trader",
                "refresh_trades",
                "wait_for_restock",
                "level_trader",
                "barter",
                "known_trades",
                "plan_trade",
                "trade_item",
                "queue_add",
                "plan_help",
                "plan_validate",
                "queue_show",
                "queue_edit",
                "look_at",
                "point_at",
                "turn",
                "sneak",
                "emote",
                "sit",
                "stand",
                "wait",
                "swap_hands",
                "set_idle_behavior",
                "set_follow_distance",
                "step",
                "back_away",
                "approach",
                "circle",
                "interact_entity",
                "mount",
                "dismount",
                "sleep",
                "throw_item",
                "extinguish_fire",
                "create_item",
                "fly_to",
                "execute_command",
                "set_goal",
                "update_goal",
                "finish_goal",
                "use_skill",
                "manage_skill",
                "request_tools",
                "start_free_time",
                "end_free_time",
                "choose_activity");
        Set<String> actual = new HashSet<>(names);
        h.assertTrue(
                actual.equals(expected),
                "registry tool set differs: missing=" + difference(expected, actual) + ", unexpected="
                        + difference(actual, expected));
        h.assertValueEqual((long) names.size(), names.stream().distinct().count(), "tool names are unique");
        h.succeed();
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new HashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    @GameTest
    public void guestSeesOnlyLookAround(GameTestHelper h) {
        List<String> names =
                FriendaiTools.create(() -> 32).specs(dev.friendai.execution.tool.AccessLevel.GUEST).stream()
                        .map(ToolSpec::name)
                        .toList();
        h.assertTrue(names.equals(List.of("look_around")), "names were " + names);
        h.succeed();
    }

    @GameTest
    public void standToolLetsSitTaskFinish(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(companion);

        ToolResult sit = run(tools, "sit", "{}");
        h.assertTrue(!sit.error(), "sit result was " + sit.content());
        h.assertTrue(companion.body().sitting(), "companion did not sit");
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("sit"))
                        .orElse(false),
                "sit task should be current");

        ToolResult stand = run(tools, "stand", "{}");
        h.assertTrue(!stand.error(), "stand result was " + stand.content());
        h.assertFalse(companion.body().sitting(), "stand tool left the companion sitting");
        h.succeedWhen(() -> h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> !task.type().equals("sit"))
                        .orElse(true),
                "sit task did not finish after stand"));
    }

    @GameTest
    public void queueAddAcceptsTaskTools(GameTestHelper h) {
        ToolSpec spec = FriendaiTools.create(() -> 32).specs().stream()
                .filter(s -> s.name().equals("queue_add"))
                .findFirst()
                .orElseThrow();
        JsonArray allowed = spec.parametersSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("steps")
                .getAsJsonObject("items")
                .getAsJsonObject("properties")
                .getAsJsonObject("tool")
                .getAsJsonArray("enum");
        List<String> names = new java.util.ArrayList<>();
        allowed.forEach(entry -> names.add(entry.getAsString()));
        for (String tool :
                List.of("place_block", "use_block", "light_area", "store_items", "take_items", "check_container")) {
            h.assertTrue(names.contains(tool), tool + " missing from " + names);
        }
        h.succeed();
    }

    @GameTest
    public void mineAtOutsideHeightIsRejected(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "mine_block_at", "{\"x\":0,\"y\":100000,\"z\":0}");
        h.assertTrue(r.error() && r.content().startsWith("y is outside the world"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void placeToolRejectsNonBlockAndPartialCoords(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult nonBlock = run(tools(c), "place_block", "{\"block\":\"minecraft:stick\"}");
        h.assertTrue(
                nonBlock.error() && nonBlock.content().equals("minecraft:stick is not a block"),
                "result was " + nonBlock.content());
        ToolResult partial = run(tools(c), "place_block", "{\"block\":\"minecraft:stone\",\"x\":1}");
        h.assertTrue(
                partial.error() && partial.content().equals("give all of x, y, z or none"),
                "result was " + partial.content());
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 4);
        for (BlockPos relative : List.of(new BlockPos(5, 2, 2), new BlockPos(9, 2, 2), new BlockPos(12, 2, 2))) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(relative)),
                    "fixture iron block should be directly visible at " + relative);
        }
        ToolResult r = run(tools(c), "find_blocks", "{\"block\":\"minecraft:iron_block\",\"limit\":2}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(
                r.content().startsWith("2 observed/remembered minecraft:iron_block nearest first: "),
                "content was " + r.content());
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "find_blocks", "{\"block\":\"minecraft:sponge\",\"radius\":8}");
        h.assertTrue(
                !r.error()
                        && r.content()
                                .equals("no minecraft:sponge observed or remembered within 8 blocks; "
                                        + "use look_around or survey_area to look around"),
                "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void queueAddRejectsInvalidSteps(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult nonPlannable = run(tools(c), "queue_add", "{\"steps\":[{\"tool\":\"stay\"}]}");
        h.assertTrue(nonPlannable.error(), "result was " + nonPlannable.content());
        ToolResult badArgs = run(
                tools(c),
                "queue_add",
                "{\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\"}\"}]}");
        h.assertTrue(
                badArgs.error() && badArgs.content().equals("Invalid plan: step 1: missing required argument 'count'"),
                "result was " + badArgs.content());
        h.succeed();
    }

    @GameTest
    public void queueAddRejectsArgsObjectInsteadOfString(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(
                tools(c),
                "queue_add",
                "{\"steps\":[{\"tool\":\"drop_items\",\"args\":{\"item\":\"minecraft:dirt\",\"count\":1}}]}");
        h.assertTrue(r.error(), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void queueAddStartsPlan(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        c.inventory().addItem(new ItemStack(Items.DRIPSTONE_BLOCK, 1));
        ToolResult r = runAs(
                tools(c),
                "queue_add",
                "{\"goal\":\"tidy\",\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dripstone_block\\\",\\\"count\\\":1}\"}]}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(!r.error() && r.content().startsWith("plan #"), "result was " + r.content());
        var queue = c.executionCoordinator().queue();
        h.assertTrue(
                queue.planCount() == 1,
                "the plan should be in the queue: jobs=" + queue.jobCount() + ", plans=" + queue.plans() + ", archive="
                        + queue.archives());
        h.succeed();
    }

    @GameTest
    public void farmToolRejectsStick(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "farm", "{\"crop\":\"minecraft:stick\"}");
        h.assertTrue(
                r.error() && r.content().equals("minecraft:stick is not something I can plant"),
                "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void breedToolRejectsHorse(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "breed_animals", "{\"target\":\"minecraft:horse\",\"pairs\":1}");
        h.assertTrue(r.error() && r.content().equals("I can't breed minecraft:horse"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void attackToolRefusals(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        ToolResult villager = runAs(
                tools,
                "attack",
                "{\"target\":\"minecraft:villager\",\"count\":1}",
                new Caller(UUID.randomUUID(), "Trusted", AccessLevel.TRUSTED));
        h.assertTrue(
                villager.error() && villager.content().equals("I won't attack minecraft:villager"),
                "a trusted player's order must not lift the rule: " + villager.content());
        ToolResult player = run(tools, "attack", "{\"target\":\"minecraft:player\",\"count\":1}");
        h.assertTrue(
                player.error() && player.content().equals("use attack_player for players"),
                "result was " + player.content());
        ToolResult unknown = run(tools, "attack", "{\"target\":\"nonsense:mob\",\"count\":1}");
        h.assertTrue(
                unknown.error() && unknown.content().equals("unknown mob: nonsense:mob"),
                "result was " + unknown.content());
        h.succeed();
    }

    @GameTest
    public void ownerOrderAttacksProtectedSpecies(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // The queued job re-reads the caller against the companion's owner, so the fixture must have one: with an
        // ownerless companion an accepted owner order becomes a guest call when the job starts.
        java.util.UUID owner = java.util.UUID.randomUUID();
        c.setOwner(owner);
        Caller ownerCaller = new Caller(owner, "", AccessLevel.OWNER);
        ToolExecutor tools = tools(c);
        ToolResult golem = runAs(tools, "attack", "{\"target\":\"minecraft:iron_golem\",\"count\":1}", ownerCaller);
        h.assertTrue(
                !golem.error() && golem.content().startsWith("task #"),
                "the owner's order should start the hunt: " + golem.content());
        h.assertTrue(
                c.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("attack"))
                        .orElse(false),
                "the hunt task should be running");
        c.executionCoordinator().stop("test");
        ToolResult companion = runAs(
                tools,
                "attack",
                "{\"target\":\"" + BuiltInRegistries.ENTITY_TYPE.getKey(FriendaiEntities.COMPANION) + "\",\"count\":1}",
                ownerCaller);
        h.assertTrue(companion.error(), "a companion must stay off limits: " + companion.content());
        h.succeed();
    }

    @GameTest
    public void setCombatSettingsTool(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "set_combat_settings", "{\"protection\":\"guard\"}");
        h.assertTrue(!r.error() && r.content().contains("protection guard"), "result was " + r.content());
        h.assertValueEqual(
                c.combatSettings().protection(),
                dev.friendai.features.combat.ProtectionPolicy.GUARD,
                "protection policy");
        h.succeed();
    }

    @GameTest
    public void combatSettingsToolsRequireOwner(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        List<String> trustedSpecs = tools.specs(AccessLevel.TRUSTED).stream()
                .map(spec -> spec.name())
                .toList();
        h.assertFalse(trustedSpecs.contains("set_combat_settings"), "TRUSTED sees set_combat_settings");
        h.assertTrue(
                tools.specs(AccessLevel.OWNER).stream()
                        .anyMatch(spec -> spec.name().equals("set_combat_settings")),
                "OWNER lacks set_combat_settings");

        Caller trusted = new Caller(null, "", AccessLevel.TRUSTED);
        Caller guest = new Caller(null, "", AccessLevel.GUEST);
        for (Caller caller : List.of(trusted, guest)) {
            ToolResult set = runAs(tools, "set_combat_settings", "{\"protection\":\"self_only\"}", caller);
            h.assertTrue(set.error(), caller.access() + " changed settings: " + set.content());
        }
        h.assertValueEqual(
                c.combatSettings(),
                dev.friendai.features.combat.CombatSettings.defaults(),
                "unauthorized calls changed settings");
        h.succeed();
    }

    @GameTest
    public void setCombatSettingsRejectsInvalidFieldsAtomically(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        Caller owner = new Caller(null, "", AccessLevel.OWNER);
        ToolResult bad = runAs(tools, "set_combat_settings", "{\"protection\":\"guard\",\"pursuit_limit\":99}", owner);
        h.assertTrue(bad.error(), "out-of-range pursuit was accepted: " + bad.content());
        h.assertValueEqual(
                c.combatSettings().protection(),
                dev.friendai.features.combat.ProtectionPolicy.BODYGUARD,
                "the valid field was applied despite the invalid one");

        ToolResult unknown = runAs(tools, "set_combat_settings", "{\"protection\":\"friendship\"}", owner);
        h.assertTrue(unknown.error(), "unknown label was accepted: " + unknown.content());
        h.assertValueEqual(
                c.combatSettings(),
                dev.friendai.features.combat.CombatSettings.defaults(),
                "settings changed after a rejected request");
        h.succeed();
    }

    @GameTest
    public void setCombatSettingsPreservesUnspecifiedFields(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        Caller owner = new Caller(null, "", AccessLevel.OWNER);
        ToolResult set = runAs(
                tools,
                "set_combat_settings",
                "{\"style\":\"ranged\",\"splash_potions\":true,\"pursuit_limit\":40,\"retreat\":\"conservative\"}",
                owner);
        h.assertFalse(set.error(), "valid settings failed: " + set.content());
        ToolResult update = runAs(tools, "set_combat_settings", "{\"protection\":\"avoid_combat\"}", owner);
        h.assertFalse(update.error(), "partial settings update failed: " + update.content());
        h.assertValueEqual(
                c.combatSettings().protection(),
                dev.friendai.features.combat.ProtectionPolicy.AVOID_COMBAT,
                "protection");
        h.assertValueEqual(c.combatSettings().style(), dev.friendai.features.combat.FightingStyle.RANGED, "style");
        h.assertTrue(c.combatSettings().allowSplashPotions(), "partial update reset potion allowance");
        h.assertValueEqual(c.combatSettings().pursuitLimitBlocks(), 40, "partial update reset pursuit limit");
        h.assertValueEqual(
                c.combatSettings().retreatPreference(),
                dev.friendai.features.combat.RetreatPreference.CONSERVATIVE,
                "partial update reset retreat preference");
        h.succeed();
    }

    @GameTest
    public void getCombatSettingsReportsPersistentPolicy(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        Caller owner = new Caller(null, "", AccessLevel.OWNER);
        runAs(tools, "set_combat_settings", "{\"protection\":\"self_only\",\"style\":\"ranged\"}", owner);
        ToolResult get = runAs(tools, "get_combat_settings", "{}", owner);
        h.assertFalse(get.error(), "get failed: " + get.content());
        h.assertTrue(get.content().contains("protection self_only"), "result was " + get.content());
        h.assertTrue(get.content().contains("style ranged"), "result was " + get.content());
        h.succeed();
    }

    @GameTest
    public void attackPlayerFollowsOwnerConsentAndTeams(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        String ownerName = "friendai-owner-" + UUID.randomUUID().toString().substring(0, 8);
        ServerPlayer owner = mockPlayerNamed(h, ownerName, 2, 2, 2);
        ServerPlayer target = mockPlayerNamed(
                h, "friendai-target-" + UUID.randomUUID().toString().substring(0, 8), 5, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        ToolExecutor tools = tools(c);

        ToolResult own = runAs(
                tools,
                "attack_player",
                "{\"name\":\"" + ownerName + "\"}",
                new Caller(owner.getUUID(), ownerName, AccessLevel.OWNER));
        h.assertTrue(
                !own.error() && own.content().startsWith("task #"),
                "the owner asking for it should start the fight: " + own.content());
        h.assertTrue(
                c.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("attack_player"))
                        .orElse(false),
                "the attack task should be running");

        ToolResult stranger = runAs(
                tools,
                "attack_player",
                "{\"name\":\"" + ownerName + "\"}",
                new Caller(UUID.randomUUID(), "Mallory", AccessLevel.OWNER));
        h.assertTrue(
                stranger.error() && stranger.content().equals("I won't attack you"),
                "another caller must not target the owner: " + stranger.content());

        PlayerTeam team = h.getLevel()
                .getScoreboard()
                .addPlayerTeam("friendai_test_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        team.setAllowFriendlyFire(false);
        h.getLevel().getScoreboard().addPlayerToTeam(owner.getName().getString(), team);
        h.getLevel().getScoreboard().addPlayerToTeam(target.getName().getString(), team);
        ToolResult friendly =
                run(tools, "attack_player", "{\"name\":\"" + target.getName().getString() + "\"}");
        h.assertTrue(
                friendly.error()
                        && friendly.content()
                                .equals("I can't hurt " + target.getName().getString() + " here"),
                "result was " + friendly.content());
        h.getLevel().getScoreboard().removePlayerTeam(team);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void ownerOrderedFightHitsTheOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        String ownerName = "friendai-hit-owner-" + UUID.randomUUID().toString().substring(0, 8);
        ServerPlayer owner = mockPlayerNamed(h, ownerName, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        ToolResult r = runAs(
                tools(c),
                "attack_player",
                "{\"name\":\"" + ownerName + "\"}",
                new Caller(owner.getUUID(), ownerName, AccessLevel.OWNER));
        h.assertTrue(!r.error(), "result was " + r.content());
        h.succeedWhen(() -> h.assertTrue(
                owner.getHealth() < owner.getMaxHealth(),
                "the owner's health is still " + owner.getHealth() + " (mode " + owner.gameMode() + ", state "
                        + c.combatController().state() + ", distance " + c.distanceTo(owner) + ", task "
                        + c.executionCoordinator()
                                .current()
                                .map(task -> task.type())
                                .orElse("none") + ")"));
    }

    @GameTest
    public void attackPlayerIsOwnerOnly(GameTestHelper h) {
        List<String> trusted = FriendaiTools.create(() -> 32).specs(AccessLevel.TRUSTED).stream()
                .map(ToolSpec::name)
                .toList();
        List<String> owner = FriendaiTools.create(() -> 32).specs(AccessLevel.OWNER).stream()
                .map(ToolSpec::name)
                .toList();
        h.assertTrue(!trusted.contains("attack_player"), "trusted tools were " + trusted);
        h.assertTrue(owner.contains("attack_player"), "owner tools were " + owner);
        h.succeed();
    }

    private static ToolResult runArgs(ToolExecutor tools, String name, JsonObject args) {
        return tools.execute(new ToolCall("1", name, args, args.toString(), null));
    }

    private static JsonObject buildArgs(String generator, BlockPos at) {
        JsonObject args = new JsonObject();
        args.addProperty("generator", generator);
        args.addProperty("x", at.getX());
        args.addProperty("y", at.getY());
        args.addProperty("z", at.getZ());
        return args;
    }

    private static final String PLATFORM_3 =
            "{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:stone\"}";

    @GameTest
    public void helpMentionsOpsAndGenerators(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "blueprint_help", "{}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("\"ops\""), "content was " + r.content());
        h.assertTrue(r.content().contains("house"), "content was " + r.content());
        h.assertTrue(r.content().contains("tower"), "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void previewText(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE, 9));
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        ToolResult r = runArgs(tools(c), "preview_structure", buildArgs(PLATFORM_3, at));
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("blocks to place"), "content was " + r.content());
        h.assertTrue(r.content().contains("materials:"), "content was " + r.content());
        h.assertTrue(r.content().contains("site: clear"), "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void previewErrorsListed(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        JsonObject args = new JsonObject();
        args.addProperty("blueprint", """
                {
                  "palette": { "C": "minecraft:not_a_block" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" } ]
                }
                """);
        ToolResult r = runArgs(tools(c), "preview_structure", args);
        h.assertTrue(r.error(), "should be an error: " + r.content());
        h.assertTrue(r.content().contains("unknown block minecraft:not_a_block"), "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void saveFromLastPreview(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE, 9));
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        ToolResult preview = runArgs(tools(c), "preview_structure", buildArgs(PLATFORM_3, at));
        h.assertTrue(!preview.error(), "preview: " + preview.content());
        String name =
                "preview_hut_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ToolResult saved = run(tools(c), "save_blueprint", "{\"name\":\"" + name + "\"}");
        h.assertTrue(!saved.error(), "error: " + saved.content());
        h.assertTrue(
                saved.content().equals("saved blueprint " + name + " (9 blocks)"), "content was " + saved.content());
        h.succeed();
    }

    @GameTest
    public void listShowsSaved(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        String name =
                "listed_tower_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonObject save = new JsonObject();
        save.addProperty("name", name);
        save.addProperty("blueprint", """
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,1,1], "block": "C" } ]
                }
                """);
        ToolResult saved = runArgs(tools(c), "save_blueprint", save);
        h.assertTrue(!saved.error(), "save: " + saved.content());
        ToolResult listed = run(tools(c), "list_blueprints", "{\"query\":\"" + name + "\"}");
        h.assertTrue(!listed.error(), "list: " + listed.content());
        h.assertTrue(listed.content().contains(name), "content was " + listed.content());
        h.assertTrue(listed.content().contains("2×2×2"), "content was " + listed.content());
        h.succeed();
    }

    @GameTest
    public void listIncludesTemplates(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "list_blueprints", "{\"query\":\"plains_small\"}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(
                r.content().contains("minecraft:village/plains/houses/plains_small_house_1"),
                "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void listLimit30(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "list_blueprints", "{}");
        h.assertTrue(!r.error(), "error: " + r.content());
        long ids = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+")
                .matcher(r.content())
                .results()
                .count();
        h.assertTrue(ids > 0 && ids <= 30, "counted " + ids + " template ids in " + r.content());
        h.succeed();
    }

    @GameTest
    public void helpMentionsStructure(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = run(tools(c), "blueprint_help", "{}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("structure"), "content was " + r.content());
        h.assertTrue(r.content().contains("gather"), "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void ownerEatOfRefusedFoodAllowsIt(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        c.inventory().addItem(new ItemStack(Items.ROTTEN_FLESH));
        ToolResult r = run(tools(c), "eat", "{\"item\":\"minecraft:rotten_flesh\"}");
        h.assertTrue(!r.error(), "result was " + r.content());
        h.assertTrue(c.foodRule("minecraft:rotten_flesh").orElse(false), "the owner naming it should allow it");
        h.assertTrue(
                c.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("eat"))
                        .orElse(false),
                "the eat task should be running");
        h.succeed();
    }

    @GameTest
    public void trustedPlayerCannotChangeFoodRules(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        c.inventory().addItem(new ItemStack(Items.ROTTEN_FLESH));
        Caller guest = new Caller(UUID.randomUUID(), "Bob", AccessLevel.TRUSTED);
        ToolResult eaten = runAs(tools(c), "eat", "{\"item\":\"minecraft:rotten_flesh\"}", guest);
        h.assertTrue(!eaten.error(), "eat result was " + eaten.content());
        h.assertTrue(
                c.foodRule("minecraft:rotten_flesh").isEmpty(), "a trusted player must not change the owner's rules");
        ToolResult allowed = runAs(tools(c), "allow_food", "{\"item\":\"minecraft:spider_eye\"}", guest);
        h.assertTrue(allowed.error(), "allow_food is owner-only, got " + allowed.content());
        h.succeed();
    }

    @GameTest
    public void foodRulesCanBeSetListedAndReset(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        ToolResult forbid = run(tools(c), "forbid_food", "{\"item\":\"minecraft:bread\"}");
        h.assertTrue(!forbid.error(), "forbid result was " + forbid.content());
        h.assertTrue(!Food.isSafe(c, new ItemStack(Items.BREAD), true), "forbidden bread must not be eaten");

        ToolResult allow = run(tools(c), "allow_food", "{\"item\":\"minecraft:spider_eye\"}");
        h.assertTrue(!allow.error(), "allow result was " + allow.content());
        h.assertTrue(Food.isSafe(c, new ItemStack(Items.SPIDER_EYE), false), "allowed spider eye must be eaten");

        ToolResult listed = run(tools(c), "get_food_rules", "{}");
        h.assertTrue(
                listed.content().contains("minecraft:bread") && listed.content().contains("minecraft:spider_eye"),
                "rules text was " + listed.content());

        ToolResult reset = run(tools(c), "reset_food_rule", "{\"item\":\"minecraft:bread\"}");
        h.assertTrue(!reset.error(), "reset result was " + reset.content());
        h.assertTrue(c.foodRule("minecraft:bread").isEmpty(), "bread should be back to the default");
        h.assertTrue(Food.isSafe(c, new ItemStack(Items.BREAD), true), "bread is safe by default");
        h.succeed();
    }

    @GameTest
    public void buildToolStartsTask(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE, 9));
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        ToolResult r = runArgs(tools(c), "build_structure", buildArgs(PLATFORM_3, at));
        h.assertTrue(!r.error() && r.content().startsWith("task #"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator().current().get().type().equals("build_structure"),
                "build task should be current");
        h.succeed();
    }

    @GameTest
    public void describeBlockReportsToolNeed(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(6, 2, 2, Blocks.IRON_ORE);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, 2, 2);
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "iron ore fixture should be visible");
        var tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        var args = "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
        var call = new dev.friendai.agent.llm.model.ToolCall(
                "1",
                "describe_block",
                com.google.gson.JsonParser.parseString(args).getAsJsonObject(),
                args,
                null);
        var result = tools.execute(
                call,
                new dev.friendai.execution.tool.Caller(
                        java.util.UUID.randomUUID(), "owner", dev.friendai.execution.tool.AccessLevel.OWNER));
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("needs a proper tool (you have none)"), result.content());

        c.inventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        var withTool = tools.execute(
                call,
                new dev.friendai.execution.tool.Caller(
                        java.util.UUID.randomUUID(), "owner", dev.friendai.execution.tool.AccessLevel.OWNER));
        h.assertTrue(withTool.content().contains("you have iron_pickaxe"), withTool.content());
        h.succeed();
    }

    @GameTest
    public void describeBlockDescribesAir(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, 2, 2);
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(4, 2, 4));
        var tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        var args = "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
        var call = new dev.friendai.agent.llm.model.ToolCall(
                "1",
                "describe_block",
                com.google.gson.JsonParser.parseString(args).getAsJsonObject(),
                args,
                null);
        var result = tools.execute(
                call,
                new dev.friendai.execution.tool.Caller(
                        java.util.UUID.randomUUID(), "owner", dev.friendai.execution.tool.AccessLevel.OWNER));
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("unknown at "), result.content());
        h.succeed();
    }

    @GameTest
    public void describeBlockSeesAnOffhandTool(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(6, 2, 2, Blocks.IRON_ORE);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, 2, 2);
        c.setItemSlot(
                net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "iron ore fixture should be visible");
        var tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        var args = "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
        var call = new dev.friendai.agent.llm.model.ToolCall(
                "1",
                "describe_block",
                com.google.gson.JsonParser.parseString(args).getAsJsonObject(),
                args,
                null);
        var result = tools.execute(
                call,
                new dev.friendai.execution.tool.Caller(
                        java.util.UUID.randomUUID(), "owner", dev.friendai.execution.tool.AccessLevel.OWNER));
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("you have iron_pickaxe"), result.content());
        h.succeed();
    }

    @GameTest
    public void describeBlockHandlesUnbreakableBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(6, 2, 2, Blocks.BEDROCK);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, 2, 2);
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(6, 2, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, c, pos), "bedrock fixture should be visible");
        var tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        var args = "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
        var call = new dev.friendai.agent.llm.model.ToolCall(
                "1",
                "describe_block",
                com.google.gson.JsonParser.parseString(args).getAsJsonObject(),
                args,
                null);
        var result = tools.execute(
                call,
                new dev.friendai.execution.tool.Caller(
                        java.util.UUID.randomUUID(), "owner", dev.friendai.execution.tool.AccessLevel.OWNER));
        h.assertTrue(result.content().contains("cannot be broken"), result.content());
        h.succeed();
    }

    @GameTest
    public void farmToolsAreRegistered(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = tools(c);
        h.assertTrue(tools.specs().stream().anyMatch(spec -> spec.name().equals("plan_farm")), "plan_farm missing");
        ToolResult r = run(tools, "farm", "{\"bonemeal\":true}");
        h.assertTrue(!r.error(), "farm refused bonemeal: " + r.content());
        h.succeed();
    }
}
