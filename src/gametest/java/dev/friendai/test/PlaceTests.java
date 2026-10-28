package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.place.PlaceService;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.FarmTask;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

public class PlaceTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        /** Set to mirror the brain's hook: what a successful task noticed lands in the owner's book. */
        CompanionEntity companion;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            this.summary = "cancelled: " + reason;
        }

        @Override
        public void onPlace(PlaceCandidate candidate, String toolName) {
            if (companion != null) {
                PlaceService.autoRegister(companion, candidate);
            }
        }
    }

    private static ToolResult runAs(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null), caller);
    }

    private static ToolExecutor tools(CompanionEntity companion) {
        return FriendaiTools.create(() -> 32).bind(companion);
    }

    /** Collects what a command sends to its source, so a command test can read the player-facing text. */
    private static final class Chat implements CommandSource {
        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return true;
        }
    }

    private static int execute(Commands commands, CommandSourceStack source, String command) {
        try {
            return commands.getDispatcher().execute(commands.getDispatcher().parse(command, source));
        } catch (CommandSyntaxException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean containsKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (translatable.getKey().equals(key)) {
                return true;
            }
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component nested && containsKey(nested, key)) {
                    return true;
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (containsKey(sibling, key)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code list.entry} message a place list sent for this name, with its rendered arguments. */
    private static Optional<TranslatableContents> entryFor(Chat chat, String name) {
        for (Component message : chat.messages) {
            if (message.getContents() instanceof TranslatableContents entry
                    && entry.getKey().equals("friendai.command.place.list.entry")
                    && entry.getArgs().length > 1
                    && name.equals(entry.getArgs()[1])) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @GameTest
    public void place_tests_access(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.setOwner(owner.getUUID());
        ToolExecutor tools = tools(companion);
        Caller guest = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.GUEST);
        Caller trusted = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED);
        Caller ownerCaller = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER);
        String remember = "{\"name\":\"home\",\"kind\":\"house\"}";

        ToolResult refused = runAs(tools, "remember_place", remember, guest);
        h.assertTrue(
                refused.error() && refused.content().contains("not available"),
                "a guest must not remember places: " + refused.content());

        ToolResult remembered = runAs(tools, "remember_place", remember, trusted);
        h.assertTrue(!remembered.error(), "a trusted speaker should remember: " + remembered.content());
        h.assertValueEqual(PlaceService.book(companion).all().size(), 1, "places after remember");

        ToolResult trustedForget = runAs(tools, "forget_place", "{\"name\":\"home\"}", trusted);
        h.assertTrue(
                trustedForget.error() && trustedForget.content().contains("not available"),
                "a trusted speaker must not forget places: " + trustedForget.content());
        h.assertValueEqual(PlaceService.book(companion).all().size(), 1, "places after refused forget");

        ToolResult forgotten = runAs(tools, "forget_place", "{\"name\":\"home\"}", ownerCaller);
        h.assertTrue(!forgotten.error(), "the owner should forget: " + forgotten.content());
        h.assertTrue(
                PlaceService.book(companion).all().isEmpty(),
                "the place should be gone: " + PlaceService.book(companion).all());
        h.succeed();
    }

    @GameTest
    public void place_tests_command_add_list(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        Commands commands = h.getLevel().getServer().getCommands();

        commands.performPrefixedCommand(owner.createCommandSourceStack(), "friendai place add shack");
        List<Place> places = PlaceService.book(companion).all();
        h.assertValueEqual(places.size(), 1, "places after add: " + places);
        Place shack = places.get(0);
        BlockPos anchor = owner.blockPosition();
        h.assertValueEqual(shack.name(), "shack", "name");
        h.assertValueEqual(shack.kind(), PlaceKind.HOUSE, "kind");
        h.assertValueEqual(shack.radius(), 8, "radius");
        h.assertValueEqual(shack.source(), PlaceSource.COMMAND, "source");
        h.assertValueEqual(shack.anchor(), new Pos(anchor.getX(), anchor.getY(), anchor.getZ()), "anchor");
        h.assertValueEqual(
                shack.dimension(), owner.level().dimension().identifier().toString(), "dimension");

        Chat chat = new Chat();
        CommandSourceStack source = owner.createCommandSourceStack().withSource(chat);
        int listed = execute(commands, source, "friendai place list");
        h.assertValueEqual(listed, 1, "list result");
        h.assertTrue(
                chat.messages.stream().anyMatch(m -> containsKey(m, "friendai.command.place.list.header")),
                "list header missing: " + chat.messages);
        h.assertTrue(
                chat.messages.stream().anyMatch(m -> containsKey(m, "friendai.command.place.list.entry")),
                "list entry missing: " + chat.messages);
        h.assertTrue(
                chat.messages.stream().anyMatch(m -> containsKey(m, "friendai.command.place.kind.house")),
                "list entry should carry the localized kind key: " + chat.messages);
        TranslatableContents shackRow = entryFor(chat, "shack").orElseThrow();
        h.assertValueEqual(shackRow.getArgs().length, 8, "list entry fields");
        h.assertValueEqual(((Number) shackRow.getArgs()[7]).intValue(), 0, "visits in the list entry");
        h.assertTrue(
                ((Component) shackRow.getArgs()[2]).getSiblings().isEmpty(),
                "a place without a label adds no label segment: " + shackRow.getArgs()[2]);

        // A labelled place renders the label inside the kind segment, so one row still fits in the chat line.
        Place lookout = PlaceService.book(companion)
                .remember(
                        new PlaceValues(
                                "lookout",
                                PlaceKind.LANDMARK,
                                "tower",
                                shack.dimension(),
                                new Pos(anchor.getX() + 3, anchor.getY(), anchor.getZ()),
                                8,
                                Set.of(),
                                PlaceSource.MANUAL,
                                owner.getUUID(),
                                ""),
                        companion.level().getGameTime());
        Chat labelled = new Chat();
        execute(commands, owner.createCommandSourceStack().withSource(labelled), "friendai place list");
        Component kindSegment =
                (Component) entryFor(labelled, "lookout").orElseThrow().getArgs()[2];
        h.assertTrue(
                kindSegment.getSiblings().stream().anyMatch(s -> s.getString().contains("tower")),
                "the kind segment should carry the label: " + kindSegment);
        PlaceService.book(companion).remove(lookout.id());

        commands.performPrefixedCommand(owner.createCommandSourceStack(), "friendai place remove shack");
        h.assertTrue(
                PlaceService.book(companion).all().isEmpty(),
                "the place should be gone: " + PlaceService.book(companion).all());

        // The sender is in the overworld while the same owner's book holds a nether place: remove must not
        // reach outside the sender's dimension, and must not mutate anything on the way to saying so.
        PlaceService.book(companion)
                .remember(
                        new PlaceValues(
                                "nether_home",
                                PlaceKind.PORTAL,
                                "",
                                "minecraft:the_nether",
                                new Pos(8, 64, 8),
                                8,
                                Set.of(),
                                PlaceSource.MANUAL,
                                owner.getUUID(),
                                ""),
                        companion.level().getGameTime());
        Chat split = new Chat();
        int removed = execute(
                commands, owner.createCommandSourceStack().withSource(split), "friendai place remove nether_home");
        h.assertValueEqual(removed, 0, "a place in another dimension must not be removed");
        h.assertTrue(
                split.messages.stream().anyMatch(m -> containsKey(m, "friendai.command.place.notfound")),
                "notfound missing: " + split.messages);
        List<Place> kept = PlaceService.book(companion).all();
        h.assertValueEqual(kept.size(), 1, "the nether place must stay: " + kept);
        h.assertValueEqual(kept.get(0).dimension(), "minecraft:the_nether", "dimension after refused remove");
        h.succeed();
    }

    @GameTest
    public void place_tests_cross_dimension(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.setOwner(owner.getUUID());
        ToolExecutor tools = tools(companion);
        Caller caller = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED);

        PlaceService.book(companion)
                .remember(
                        new PlaceValues(
                                "home",
                                PlaceKind.PORTAL,
                                "",
                                "minecraft:the_nether",
                                new Pos(8, 64, 8),
                                8,
                                Set.of(),
                                PlaceSource.MANUAL,
                                owner.getUUID(),
                                ""),
                        companion.level().getGameTime());

        ToolResult cross = runAs(tools, "go_to_place", "{\"name\":\"home\"}", caller);
        h.assertTrue(cross.error(), "a cross-dimension place must be refused: " + cross.content());
        h.assertTrue(
                cross.content().contains("minecraft:the_nether"),
                "the error should name the dimension: " + cross.content());
        h.assertTrue(
                cross.content().contains("cannot cross dimensions"), "the error should say why: " + cross.content());
        h.assertTrue(
                companion.executionCoordinator().current().isEmpty(),
                "no task may start: "
                        + companion
                                .executionCoordinator()
                                .current()
                                .map(task -> task.describe())
                                .orElse("none"));

        ToolResult unknown = runAs(tools, "go_to_place", "{\"name\":\"nowhere\"}", caller);
        h.assertTrue(
                unknown.error() && unknown.content().equals("no place called nowhere; use find_place"),
                "an unknown name keeps the original message: " + unknown.content());
        h.assertTrue(companion.executionCoordinator().current().isEmpty(), "no task may start for an unknown name");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void place_tests_near_place_scopes_a_search(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
        companion.setOwner(owner.getUUID());
        ToolExecutor tools = tools(companion);
        Caller caller = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED);
        BlockPos anchor = h.absolutePos(new BlockPos(8, 2, 3));

        ToolResult remembered = runAs(
                tools,
                "remember_place",
                "{\"name\":\"mine\",\"kind\":\"mine\",\"x\":" + anchor.getX() + ",\"y\":" + anchor.getY() + ",\"z\":"
                        + anchor.getZ() + ",\"radius\":4}",
                caller);
        h.assertTrue(!remembered.error(), "remember failed: " + remembered.content());
        PlaceService.book(companion)
                .remember(
                        new PlaceValues(
                                "nethermine",
                                PlaceKind.MINE,
                                "",
                                "minecraft:the_nether",
                                new Pos(8, 64, 8),
                                4,
                                Set.of(),
                                PlaceSource.MANUAL,
                                owner.getUUID(),
                                ""),
                        companion.level().getGameTime());

        ToolResult unknown = runAs(
                tools, "mine_blocks", "{\"block\":\"minecraft:stone\",\"count\":1,\"near\":\"place:nowhere\"}", caller);
        h.assertTrue(
                unknown.error() && unknown.content().contains("no place called nowhere"),
                "an unknown place keeps the original message: " + unknown.content());
        h.assertTrue(
                companion.executionCoordinator().current().isEmpty(),
                "no task may start for an unknown place: " + current(companion));

        ToolResult cross = runAs(
                tools,
                "mine_blocks",
                "{\"block\":\"minecraft:stone\",\"count\":1,\"near\":\"place:nethermine\"}",
                caller);
        h.assertTrue(
                cross.error() && cross.content().contains("minecraft:the_nether"),
                "a cross-dimension place should name its dimension: " + cross.content());
        h.assertTrue(
                cross.content().contains("cannot cross dimensions"),
                "a cross-dimension place should say why: " + cross.content());
        h.assertTrue(
                companion.executionCoordinator().current().isEmpty(),
                "no task may start across dimensions: " + current(companion));

        ToolResult scoped = runAs(
                tools, "mine_blocks", "{\"block\":\"minecraft:stone\",\"count\":1,\"near\":\"Place:mine\"}", caller);
        h.assertTrue(!scoped.error(), "mining near a place failed: " + scoped.content());
        String described = current(companion);
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("mine_blocks"))
                        .orElse(false),
                "the mine_blocks task should be current: " + described);
        String where = anchor.getX() + " " + anchor.getY() + " " + anchor.getZ();
        h.assertTrue(
                described.contains(where),
                "the task should search around the place anchor " + where + ": " + described);
        h.succeed();
    }

    private static String current(CompanionEntity companion) {
        return companion
                .executionCoordinator()
                .current()
                .map(task -> task.describe())
                .orElse("none");
    }

    @GameTest(maxTicks = 400)
    public void place_tests_remember_and_go(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 12, 2, 2);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        companion.executionCoordinator().setListener(result);
        ToolExecutor tools = tools(companion);
        Caller caller = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED);

        ToolResult remembered =
                runAs(tools, "remember_place", "{\"name\":\"home\",\"kind\":\"house\",\"radius\":8}", caller);
        h.assertTrue(!remembered.error(), "remember failed: " + remembered.content());
        List<Place> places = PlaceService.book(companion).all();
        h.assertValueEqual(places.size(), 1, "places after remember");
        Place place = places.get(0);
        h.assertValueEqual(place.name(), "home", "stored name");
        h.assertValueEqual(place.anchor().x(), owner.blockPosition().getX(), "anchor x follows the speaker");
        h.assertValueEqual(place.anchor().z(), owner.blockPosition().getZ(), "anchor z follows the speaker");

        ToolResult go = runAs(tools, "go_to_place", "{\"name\":\"home\"}", caller);
        h.assertTrue(!go.error(), "go failed: " + go.content());
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("go_to_place")
                                && task.describe().equals("walking to home"))
                        .orElse(false),
                "the go_to_place task should be current");

        double successRadius = Math.max(2, Math.min(place.radius(), 8));
        h.succeedWhen(() -> {
            companion.executionCoordinator().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            Place visited = PlaceService.book(companion).byId(place.id()).orElseThrow();
            h.assertValueEqual(visited.visits(), 1, "visits");
            double horizontal = Math.hypot(
                    companion.getX() - (visited.anchor().x() + 0.5),
                    companion.getZ() - (visited.anchor().z() + 0.5));
            h.assertTrue(
                    horizontal <= successRadius, "arrived " + horizontal + " blocks away, allowed " + successRadius);
        });
    }

    /** A 3x3 wheat plot around (5, 2, 5) whose crops are ready for the first pass. */
    private static void wheatPlot(GameTestHelper h) {
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                if (x == 5 && z == 5) {
                    h.setBlock(x, 1, z, Blocks.WATER);
                    continue;
                }
                h.setBlock(x, 1, z, Blocks.FARMLAND);
                h.setBlock(x, 2, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
        }
    }

    @GameTest(maxTicks = 1200)
    public void place_tests_farm_registers(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        wheatPlot(h);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
        companion.setOwner(owner.getUUID());
        companion.inventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 8));
        Result result = new Result();
        result.companion = companion;
        companion.executionCoordinator().setListener(result);
        BlockPos plot = h.absolutePos(new BlockPos(5, 2, 5));
        companion.executionCoordinator().start(new FarmTask(plot, 4, Items.WHEAT_SEEDS, false), "farm");

        int[] phase = {0};
        long[] created = {0};
        h.succeedWhen(() -> {
            companion.executionCoordinator().tick();
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS,
                    (phase[0] == 0 ? "the first" : "the second") + " farm pass should finish: " + result.status + " "
                            + result.summary);
            List<Place> places = PlaceService.book(companion).all();
            h.assertValueEqual(places.size(), 1, "places after pass " + (phase[0] + 1) + ": " + places);
            Place farm = places.get(0);
            h.assertValueEqual(farm.kind(), PlaceKind.FARM, "kind");
            h.assertValueEqual(farm.label(), "wheat", "label");
            h.assertValueEqual(farm.source(), PlaceSource.FARM, "source");
            h.assertValueEqual(farm.radius(), 4, "radius");
            h.assertValueEqual(farm.anchor(), new Pos(plot.getX(), plot.getY(), plot.getZ()), "anchor");
            if (phase[0] == 0) {
                // Ripen the replanted crop and work the very same plot again: the place must be refreshed, not
                // recorded twice. Nothing below is asserted until that second pass has finished.
                created[0] = farm.createdTick();
                phase[0] = 1;
                wheatPlot(h);
                result.status = null;
                result.summary = "";
                companion.executionCoordinator().start(new FarmTask(plot, 4, Items.WHEAT_SEEDS, false), "farm");
                h.assertTrue(false, "waiting for the second farm pass to finish");
            }
            h.assertValueEqual(farm.createdTick(), created[0], "a refresh keeps createdTick");
            h.assertTrue(
                    farm.updatedTick() > created[0],
                    "a refresh stamps updatedTick " + farm.updatedTick() + " after " + created[0]);
        });
    }
}
