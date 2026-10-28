package dev.friendai.test;

import com.mojang.authlib.GameProfile;
import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.inventory.minecraft.task.DepositTask;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.place.PlaceService;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class FreeTimeTests {
    private record MockOwner(net.minecraft.server.level.ServerPlayer player, EmbeddedChannel channel) {}

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

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setListener(result);
        return r;
    }

    /** A companion with its own owner, so its place book is not shared with other tests. */
    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        return c;
    }

    private static void storagePlace(GameTestHelper h, CompanionEntity c, String name, int x, int y, int z, int r) {
        BlockPos anchor = h.absolutePos(new BlockPos(x, y, z));
        PlaceService.book(c)
                .remember(
                        new PlaceValues(
                                name,
                                PlaceKind.STORAGE,
                                "",
                                c.level().dimension().identifier().toString(),
                                new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                                r,
                                Set.of(),
                                PlaceSource.MANUAL,
                                c.ownerUuid(),
                                ""),
                        c.level().getGameTime());
    }

    /** Drains the companion's own hunger to {@code target} without waiting for vanilla depletion. */
    private static void drainHunger(GameTestHelper h, CompanionEntity c, int target) {
        while (c.hunger().food() > target) {
            c.hunger().addExhaustion(4.0f);
            c.hunger().tick(dev.friendai.features.survival.Difficulty.NORMAL, true, c.getHealth(), c.getMaxHealth());
        }
    }

    /** The reflex tests that need daylight set it explicitly; the GameTest world may have started at night. */
    private static void noon(GameTestHelper h) {
        var server = h.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
    }

    private static Container chest(GameTestHelper h, int x, int y, int z) {
        return (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, y, z)));
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                total += container.getItem(i).getCount();
            }
        }
        return total;
    }

    private static int bag(CompanionEntity c, String id) {
        return InventoryOps.count(c.inventory(), ItemMatcher.parse(id).orElseThrow());
    }

    @GameTest(maxTicks = 600)
    public void depositLikeWithLike(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        h.setBlock(6, 2, 9, Blocks.CHEST);
        chest(h, 6, 2, 6).setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        CompanionEntity c = companion(h);
        storagePlace(h, c, "deposit chests", 6, 2, 7, 3);
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 52));
        c.inventory().addItem(new ItemStack(Items.RAW_IRON, 10));
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.BREAD, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new DepositTask("deposit chests", AutonomySettings.DEFAULT_KEEP), "deposit_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(count(chest(h, 6, 2, 6), Items.COBBLESTONE), 21, "cobblestone joins the cobble chest");
            h.assertValueEqual(count(chest(h, 6, 2, 9), Items.RAW_IRON), 10, "raw iron goes to the empty chest");
            h.assertValueEqual(bag(c, "minecraft:cobblestone"), 32, "building blocks kept");
            h.assertValueEqual(bag(c, "minecraft:iron_pickaxe"), 1, "the pickaxe is kept");
            h.assertValueEqual(bag(c, "minecraft:bread"), 4, "food for 20 points is kept");
            h.assertValueEqual(bag(c, "minecraft:raw_iron"), 0, "ore is deposited");
        });
    }

    /** Finishes on its first tick, like a quick owner order. */
    private static final class QuickTask implements dev.friendai.task.Task {
        @Override
        public String type() {
            return "quick";
        }

        @Override
        public void start(dev.friendai.task.TaskContext c) {}

        @Override
        public TaskStatus tick(dev.friendai.task.TaskContext c) {
            return TaskStatus.SUCCESS;
        }

        @Override
        public void stop(dev.friendai.task.TaskContext c) {}

        @Override
        public String describe() {
            return "quick";
        }

        @Override
        public String resultSummary() {
            return "done";
        }
    }

    @GameTest(maxTicks = 200)
    public void marker(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = companion(h);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        var stay = new dev.friendai.execution.queue.QueuedStep(
                "stay",
                new com.google.gson.JsonObject(),
                null,
                java.util.List.of(),
                dev.friendai.execution.queue.GuardAction.WAIT,
                dev.friendai.execution.queue.FailurePolicy.ASK,
                1,
                java.util.List.of());
        long plan = runner.enqueuePlan(
                "free time",
                java.util.List.of(stay),
                java.util.List.of(ctx -> new dev.friendai.features.navigation.minecraft.task.StayTask()),
                new dev.friendai.execution.queue.PlanOptions(-10, dev.friendai.execution.queue.FailurePolicy.ASK, 0));
        runner.markAutonomousPlan(plan);
        runner.tick();
        h.assertTrue(runner.autonomousActive(), "the free-time job runs");
        runner.start(new QuickTask(), "quick");
        h.assertTrue(!runner.autonomousActive(), "an owner order is not free time");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(runner.autonomousActive(), "the free-time job resumes after the owner order");
            runner.unmarkAutonomousPlan(plan);
            h.assertTrue(!runner.autonomousActive(), "an unmarked plan is not free time");
            runner.markAutonomousPlan(plan);
        });
    }

    @GameTest
    public void deniedToolsAreRefused(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = companion(h);
        var builtins = dev.friendai.tool.builtin.FriendaiTools.build(
                () -> 16, dev.friendai.tool.builtin.MemoryAccess.DISABLED);
        var args = com.google.gson.JsonParser.parseString(
                        "{\"goal\":\"g\",\"priority\":-10,\"steps\":[{\"tool\":\"stay\"},{\"tool\":\"attack_player\","
                                + "\"args\":\"{\\\"player\\\":\\\"x\\\"}\"}]}")
                .getAsJsonObject();
        var caller = new dev.friendai.execution.tool.Caller(
                c.ownerUuid(), "owner", dev.friendai.execution.tool.AccessLevel.OWNER);
        var refused = dev.friendai.tool.builtin.FriendaiTools.addPlan(
                c, caller, args, builtins.plannable(), false, Set.of("attack_player"));
        h.assertTrue(refused.error(), "a denied step must be refused: " + refused.content());
        h.assertTrue(refused.content().contains("attack_player is not allowed in free time"), refused.content());
        h.assertValueEqual(c.executionCoordinator().queue().planCount(), 0, "nothing queued");
        var ok = dev.friendai.tool.builtin.FriendaiTools.addPlan(
                c,
                caller,
                com.google.gson.JsonParser.parseString(
                                "{\"goal\":\"g\",\"priority\":-10,\"steps\":[{\"tool\":\"stay\"}]}")
                        .getAsJsonObject(),
                builtins.taskTools(),
                false,
                Set.of("attack_player"));
        h.assertTrue(!ok.error(), "an allowed plan is queued: " + ok.content());
        h.assertValueEqual(c.executionCoordinator().queue().plans().getFirst().priority(), -10, "free-time priority");
        h.succeed();
    }

    private static dev.friendai.server.agent.FreeTimeDriver driver(GameTestHelper h) {
        return dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions()
                .freeTime();
    }

    /** A registered companion, so the server's free-time tick sees it. */
    private static CompanionEntity registered(GameTestHelper h, UUID owner) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner);
        dev.friendai.entity.CompanionManager.get(h.getLevel().getServer()).register(c);
        return c;
    }

    @SuppressWarnings("removal")
    private static MockOwner mockOwner(GameTestHelper h) {
        var server = h.getLevel().getServer();
        GameProfile profile = new GameProfile(
                UUID.randomUUID(), "free-time-" + UUID.randomUUID().toString().substring(0, 8));
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        var player =
                new net.minecraft.server.level.ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(3, 2, 3));
        player.teleportTo(pos.x, pos.y, pos.z);
        return new MockOwner(player, channel);
    }

    private static List<Component> drainSystemMessages(EmbeddedChannel channel) {
        List<Component> messages = new ArrayList<>();
        Object packet;
        while ((packet = channel.outboundMessages().poll()) != null) {
            if (packet instanceof ClientboundSystemChatPacket chat) {
                messages.add(chat.content());
            }
        }
        return messages;
    }

    private static boolean containsTranslation(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translated
                && translated.getKey().equals(key)) {
            return true;
        }
        return component.getSiblings().stream().anyMatch(sibling -> containsTranslation(sibling, key));
    }

    /** Queues a free-time shift by hand: one plan the driver owns, the way a reflex would. */
    private static long shiftPlan(GameTestHelper h, CompanionEntity c, String steps) {
        var builtins = dev.friendai.tool.builtin.FriendaiTools.build(
                () -> 16, dev.friendai.tool.builtin.MemoryAccess.DISABLED);
        var caller = new dev.friendai.execution.tool.Caller(
                c.ownerUuid(), "", dev.friendai.execution.tool.AccessLevel.OWNER);
        var result = dev.friendai.tool.builtin.FriendaiTools.addPlan(
                c,
                caller,
                com.google.gson.JsonParser.parseString("{\"goal\":\""
                                + dev.friendai.server.agent.FreeTimeDriver.PLAN_PREFIX + "test\",\"priority\":-10,"
                                + "\"on_failure\":\"stop_plan\",\"steps\":" + steps + "}")
                        .getAsJsonObject(),
                builtins.taskTools(),
                false,
                Set.of());
        h.assertTrue(!result.error(), result.content());
        long plan = c.executionCoordinator().queue().plans().stream()
                .mapToLong(dev.friendai.execution.queue.PlanRecord::id)
                .max()
                .orElseThrow();
        c.executionCoordinator().markAutonomousPlan(plan);
        var service = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .freeTime();
        service.beginShift(
                c.ownerUuid(),
                "free time",
                java.util.List.of(),
                "",
                dev.friendai.agent.autonomy.NeedKind.EXPLORE,
                true,
                0,
                Long.MAX_VALUE);
        service.linkPlan(c.ownerUuid(), plan);
        return plan;
    }

    @GameTest(maxTicks = 800)
    public void unloadReflex(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        UUID owner = UUID.randomUUID();
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setGatewayOverride(owner, (system, messages, tools) -> {
            throw new AssertionError("a reflex must not ask the model");
        });
        CompanionEntity c = registered(h, owner);
        storagePlace(h, c, "unload chest", 6, 2, 6, 2);
        for (int i = 0; i < 25; i++) {
            c.inventory().setItem(i, new ItemStack(Items.ANDESITE, 64));
        }
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        h.succeedWhen(() -> {
            h.assertTrue(count(chest(h, 6, 2, 6), Items.ANDESITE) > 0, "the reflex deposits the loot");
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 400)
    public void ownerOrderPauses(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        UUID owner = UUID.randomUUID();
        CompanionEntity c = registered(h, owner);
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        long plan = shiftPlan(h, c, "[{\"tool\":\"stay\"}]");
        c.executionCoordinator().tick();
        h.assertTrue(c.executionCoordinator().autonomousActive(), "the free-time job runs");
        c.executionCoordinator().start(new QuickTask(), "quick");
        h.assertTrue(!c.executionCoordinator().autonomousActive(), "the owner order runs first");
        h.succeedWhen(() -> {
            h.assertTrue(c.executionCoordinator().autonomousActive(), "the free-time job resumes");
            var shift = driver(h).state(owner).shift();
            h.assertTrue(shift != null && shift.planId() == plan, "the shift is kept: " + shift);
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 200)
    public void stopEnds(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        UUID owner = UUID.randomUUID();
        CompanionEntity c = registered(h, owner);
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        long plan = shiftPlan(h, c, "[{\"tool\":\"stay\"}]");
        var caller = new dev.friendai.execution.tool.Caller(owner, "", dev.friendai.execution.tool.AccessLevel.OWNER);
        var builtins = dev.friendai.tool.builtin.FriendaiTools.build(
                () -> 16, dev.friendai.tool.builtin.MemoryAccess.DISABLED);
        dev.friendai.tool.builtin.FriendaiTools.addPlan(
                c,
                caller,
                com.google.gson.JsonParser.parseString("{\"goal\":\"owner\",\"steps\":[{\"tool\":\"stay\"}]}")
                        .getAsJsonObject(),
                builtins.taskTools(),
                true,
                Set.of());
        long ownerPlan = c.executionCoordinator().queue().plans().stream()
                .mapToLong(dev.friendai.execution.queue.PlanRecord::id)
                .max()
                .orElseThrow();
        driver(h).stop(owner, "owner asked to stop");
        h.assertTrue(!driver(h).state(owner).on(), "free time is off");
        h.assertTrue(
                c.executionCoordinator().queue().plan(plan).isEmpty()
                        || c.executionCoordinator().queue().planStatus(plan)
                                != dev.friendai.execution.queue.PlanStatus.ACTIVE,
                "the shift plan is cancelled");
        h.assertTrue(c.executionCoordinator().queue().plan(ownerPlan).isPresent(), "the owner plan is untouched");
        h.assertValueEqual(driver(h).state(owner).history().get(0).summary(), "owner asked to stop", "record");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void idleStart(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        UUID owner = player.getUUID();
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setGatewayOverride(
                owner,
                (system, messages, tools) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("no model in tests")));
        CompanionEntity c = registered(h, owner);
        driver(h).ownerActivity(owner, System.currentTimeMillis() - 6 * 60_000L);
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            h.assertTrue(state.on(), "free time starts after the idle spell");
            h.assertValueEqual(state.origin(), dev.friendai.agent.autonomy.FreeTime.Origin.IDLE, "origin");
            driver(h).stop(owner, "test over");
        });
    }

    /**
     * The report that started item 16: a shift is running, the owner says "don't follow me", and the companion never
     * works again. A stay must hold the job, not lose it, and must end by itself.
     */
    @GameTest(maxTicks = 600)
    public void stayDuringAShiftHoldsThenResumes(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        UUID owner = UUID.randomUUID();
        CompanionEntity c = registered(h, owner);
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        long plan = shiftPlan(h, c, "[{\"tool\":\"jump\",\"args\":\"{}\"}]");
        c.executionCoordinator().tick();
        h.assertTrue(
                c.executionCoordinator().autonomousActive(),
                "the shift job runs: " + c.executionCoordinator().currentToolName());
        c.executionCoordinator().interrupt(new dev.friendai.features.navigation.minecraft.task.StayTask(40), "stay");
        h.assertTrue(c.executionCoordinator().movementOrderActive(), "the stay is a movement order, not owner work");
        h.assertTrue(!c.executionCoordinator().busyWithOther(), "a movement order is not work in the way");
        h.assertTrue(c.executionCoordinator().queue().plan(plan).isPresent(), "the held plan is still queued");
        var shift = driver(h).state(owner).shift();
        h.assertTrue(shift != null && shift.planId() == plan, "the shift is kept, not closed: " + shift);
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(!c.executionCoordinator().movementOrderActive(), "the stay ends at its timeout");
            h.assertTrue(
                    c.executionCoordinator().autonomousActive(),
                    "the shift job resumes after the stay: "
                            + c.executionCoordinator().currentToolName());
            h.assertTrue(driver(h).state(owner).on(), "free time is still on");
            driver(h).stop(owner, "test over");
        });
    }

    /** "Don't follow me" switches the idle walk off and the choice is stored with the owner, not the entity. */
    @GameTest
    public void followModeGatesTheDefaultWalk(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = companion(h);
        c.setFollowMode(false);
        h.assertTrue(!c.followMode(), "the owner's choice is stored");
        h.assertTrue(!c.mayFollowOwner(), "a companion told not to follow stays put");
        c.executionCoordinator().tick();
        h.assertTrue(
                !c.executionCoordinator().currentIsDefaultActivity(),
                "no default follow starts: " + c.executionCoordinator().currentToolName());
        h.assertTrue(
                dev.friendai.entity.CompanionLifeData.get(h.getLevel().getServer())
                                .followModeChoice(c.ownerUuid())
                                .orElseThrow()
                        == Boolean.FALSE,
                "per-owner, so it survives a relog");
        c.setFollowMode(true);
        h.assertTrue(c.followMode(), "the switch turns back on");
        h.assertTrue(c.mayFollowOwner(), "following is allowed again");
        c.executionCoordinator().tick();
        h.assertTrue(c.executionCoordinator().currentIsDefaultActivity(), "the default follow is back");
        h.succeed();
    }

    /** "Come back" must turn following on even when free time was already off. */
    @GameTest(maxTicks = 200)
    public void endFreeTimeBringsTheCompanionBack(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        c.setFollowMode(false);
        h.assertTrue(!c.followMode(), "following is off");
        var result = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions()
                .runTool(owner, "end_free_time", "{}");
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(c.followMode(), "the order to come back turns following on: " + result.content());
        h.succeed();
    }

    private static dev.friendai.agent.plan.PlanProposal jumpPlan() {
        var step = new dev.friendai.execution.queue.QueuedStep(
                "jump",
                new com.google.gson.JsonObject(),
                null,
                java.util.List.of(),
                dev.friendai.execution.queue.GuardAction.WAIT,
                null,
                1,
                java.util.List.of());
        return new dev.friendai.agent.plan.PlanProposal(
                new dev.friendai.execution.queue.PlanDefinition(java.util.List.of(
                        new dev.friendai.execution.queue.PlanNode.Action(step),
                        new dev.friendai.execution.queue.PlanNode.Action(step))),
                com.google.gson.JsonParser.parseString(
                                "[{\"tool\":\"jump\",\"args\":\"{}\"},{\"tool\":\"jump\"," + "\"args\":\"{}\"}]")
                        .getAsJsonArray(),
                "",
                "");
    }

    /** An owner who is online, whose brain never reaches a real model. */
    private static CompanionEntity onlineCompanion(GameTestHelper h) {
        var player = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setGatewayOverride(
                player.getUUID(),
                (system, messages, tools) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("no model in tests")));
        return registered(h, player.getUUID());
    }

    @GameTest(maxTicks = 400)
    public void hungerReflexEats(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        drainHunger(h, c, 6);
        c.inventory().addItem(new ItemStack(Items.BREAD, 3));
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            h.assertTrue(
                    c.hunger().food() > 6, "the companion ate: " + c.hunger().food() + " " + state);
            h.assertTrue(
                    state.shift() != null && state.shift().need() == dev.friendai.agent.autonomy.NeedKind.HUNGER
                            || state.history().stream()
                                    .anyMatch(r -> r.need() == dev.friendai.agent.autonomy.NeedKind.HUNGER),
                    "the shift is a hunger reflex: " + state);
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 900)
    public void farmCreationRegistersPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        noon(h);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        housePlace(h, c, "home", 4, 2, 4, 6);
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                if (x == 4 && z == 4) {
                    h.setBlock(x, 1, z, Blocks.WATER);
                } else {
                    h.setBlock(x, 1, z, Blocks.FARMLAND);
                }
            }
        }
        c.inventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 8));
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            boolean farm = dev.friendai.place.PlaceService.book(c).all().stream()
                    .anyMatch(place -> place.kind() == PlaceKind.FARM);
            h.assertTrue(farm, "the created farm is remembered: " + state);
            h.assertTrue(
                    state.history().stream()
                            .anyMatch(record -> record.need() == dev.friendai.agent.autonomy.NeedKind.FARM_CREATE),
                    "a farm-creation shift happened: " + state);
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 300)
    public void plannerFailureFallsBack(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setPlannerOverride(
                owner,
                input -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("no plan")));
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        boolean[] chosen = {false};
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            if (!chosen[0]) {
                h.assertTrue(state.awaitingChoice(), "the director is asked to choose");
                var result = driver(h).choose(c, "mine stone", java.util.List.of(), "", 5, "GEAR");
                h.assertTrue(!result.error(), result.content());
                chosen[0] = true;
                h.assertTrue(false, "wait for the planner failure and the fallback");
            }
            h.assertTrue(
                    state.shift() != null && state.shift().planId() != 0,
                    "the deterministic template kept the shift: " + state);
            h.assertTrue(
                    !state.cooldownUntil().containsKey(dev.friendai.agent.autonomy.NeedKind.GEAR),
                    "the need did not fail, so it is not cooling: " + state.cooldownUntil());
            driver(h).stop(owner, "test over");
        });
    }

    /** A silent director must not leave an hour empty: after the grace window a template starts. */
    @GameTest(maxTicks = 500)
    public void silentDirectorFallsBack(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        var serverState = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer());
        var gson = new com.google.gson.Gson();
        var originalConfig =
                gson.fromJson(gson.toJson(serverState.config()), dev.friendai.infrastructure.config.ServerConfig.class);
        var shortWatchdogConfig =
                gson.fromJson(gson.toJson(originalConfig), dev.friendai.infrastructure.config.ServerConfig.class);
        shortWatchdogConfig.tasks.freeTimeWatchdogSec = 1;
        serverState.applyConfig(shortWatchdogConfig);
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            h.assertTrue(
                    state.shift() != null
                            || !c.executionCoordinator().queue().plans().isEmpty()
                            || state.awaitingChoice(),
                    "the silent director does not leave free time idle: " + state);
            serverState.applyConfig(originalConfig);
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 300)
    public void directorShift(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setPlannerOverride(owner, input -> {
            h.assertTrue(!input.toolDigest().contains("attack_player"), "denied tools are not offered");
            return java.util.concurrent.CompletableFuture.completedFuture(jumpPlan());
        });
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        boolean[] chosen = {false};
        long[] plan = {0};
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            if (!chosen[0]) {
                h.assertTrue(state.awaitingChoice(), "the director is asked to choose");
                var result = driver(h)
                        .choose(c, "practise jumping", java.util.List.of(), "I'll stretch my legs", 5, "EXPLORE");
                h.assertTrue(!result.error(), result.content());
                chosen[0] = true;
                h.assertTrue(false, "wait for the planner");
            }
            if (plan[0] == 0) {
                h.assertTrue(state.shift() != null && state.shift().planId() != 0, "the plan is linked: " + state);
                plan[0] = state.shift().planId();
                h.assertValueEqual(
                        c.executionCoordinator()
                                .queue()
                                .plan(plan[0])
                                .orElseThrow()
                                .priority(),
                        -10,
                        "priority");
            }
            h.assertTrue(state.shift() == null || state.shift().planId() != plan[0], "the shift ends with its plan");
            h.assertValueEqual(state.history().get(0).goal(), "practise jumping", "history");
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 400)
    public void aFinishedTemplateClearsTheBackoff(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        for (int i = 0; i < dev.friendai.agent.tick.NudgeBackoff.MAX_STREAK; i++) {
            driver(h).budget(owner).failed();
        }
        h.assertTrue(
                !driver(h).budget(owner).backoffAllows(System.currentTimeMillis(), 60), "the director is backed off");
        shiftPlan(h, c, "[{\"tool\":\"jump\",\"args\":\"{}\"}]");
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            h.assertTrue(state.shift() == null, "the plan ends: " + state);
            h.assertValueEqual(driver(h).budget(owner).failStreak(), 0, "a good shift clears the backoff");
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 300)
    public void latePlanAfterStopIsDropped(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        var pending = new java.util.concurrent.CompletableFuture<dev.friendai.agent.plan.PlanProposal>();
        brains.setPlannerOverride(owner, input -> pending);
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        boolean[] stopped = {false};
        h.succeedWhen(() -> {
            if (!stopped[0]) {
                h.assertTrue(driver(h).state(owner).awaitingChoice(), "the director is asked to choose");
                driver(h).choose(c, "practise jumping", java.util.List.of(), "", 5, "EXPLORE");
                driver(h).stop(owner, "owner ended free time");
                pending.complete(jumpPlan());
                stopped[0] = true;
                h.assertTrue(false, "let the late answer arrive");
            }
            h.assertValueEqual(c.executionCoordinator().queue().planCount(), 0, "a late plan is not queued");
            h.assertTrue(!driver(h).state(owner).on(), "free time stays off");
        });
    }

    @GameTest(maxTicks = 600)
    public void awayLinesWaitForTheOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        Container full = chest(h, 6, 2, 6);
        for (int i = 0; i < full.getContainerSize(); i++) {
            full.setItem(i, new ItemStack(Items.TUFF, 64));
        }
        UUID owner = UUID.randomUUID();
        CompanionEntity c = registered(h, owner);
        storagePlace(h, c, "away chest", 6, 2, 6, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 5));
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        shiftPlan(h, c, "[{\"tool\":\"deposit_items\",\"args\":\"{\\\"place\\\":\\\"away chest\\\"}\"}]");
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            h.assertTrue(state.shift() == null, "the failed shift ends");
            h.assertTrue(state.history().get(0).summary().contains("storage is full"), "record: " + state.history());
            h.assertTrue(
                    state.cooldownUntil().containsKey(dev.friendai.agent.autonomy.NeedKind.EXPLORE),
                    "the need cools down");
            h.assertTrue(state.lessons().isEmpty(), "a template failing teaches the director nothing");
            h.assertTrue(
                    state.away().stream().anyMatch(line -> !line.isBlank()),
                    "the line waits for the offline owner: " + state.away());
            driver(h).stop(owner, "test over");
        });
    }

    /** Breaks one block with the shared breaker and remembers how it went. */
    private static final class BreakTask implements dev.friendai.task.Task {
        private final BlockPos pos;
        private dev.friendai.features.mining.minecraft.mining.BlockBreaker breaker;
        dev.friendai.features.mining.minecraft.mining.BlockBreaker.State last;

        BreakTask(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public String type() {
            return "break_test";
        }

        @Override
        public void start(dev.friendai.task.TaskContext c) {
            breaker = new dev.friendai.features.mining.minecraft.mining.BlockBreaker(pos, state -> !state.isAir());
        }

        @Override
        public TaskStatus tick(dev.friendai.task.TaskContext c) {
            last = breaker.tick(c);
            return last == dev.friendai.features.mining.minecraft.mining.BlockBreaker.State.WORKING
                    ? TaskStatus.RUNNING
                    : last == dev.friendai.features.mining.minecraft.mining.BlockBreaker.State.BROKEN
                            ? TaskStatus.SUCCESS
                            : TaskStatus.FAILED;
        }

        @Override
        public void stop(dev.friendai.task.TaskContext c) {
            breaker.stop(c);
        }

        @Override
        public String describe() {
            return "break";
        }

        @Override
        public String resultSummary() {
            return String.valueOf(last);
        }
    }

    private static BreakTask breakIn(CompanionEntity c, BlockPos pos, boolean autonomous) {
        BreakTask task = new BreakTask(pos);
        var step = new dev.friendai.execution.queue.QueuedStep(
                "break_test",
                new com.google.gson.JsonObject(),
                null,
                java.util.List.of(),
                dev.friendai.execution.queue.GuardAction.WAIT,
                dev.friendai.execution.queue.FailurePolicy.STOP_PLAN,
                1,
                java.util.List.of());
        long plan = c.executionCoordinator()
                .enqueuePlan(
                        "break",
                        java.util.List.of(step),
                        java.util.List.of(ctx -> task),
                        new dev.friendai.execution.queue.PlanOptions(
                                autonomous ? -10 : 0, dev.friendai.execution.queue.FailurePolicy.STOP_PLAN, 0));
        if (autonomous) {
            c.executionCoordinator().markAutonomousPlan(plan);
        }
        return task;
    }

    private static void housePlace(GameTestHelper h, CompanionEntity c, String name, int x, int y, int z, int r) {
        BlockPos anchor = h.absolutePos(new BlockPos(x, y, z));
        PlaceService.book(c)
                .remember(
                        new PlaceValues(
                                name,
                                PlaceKind.HOUSE,
                                "",
                                c.level().dimension().identifier().toString(),
                                new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                                r,
                                Set.of(),
                                PlaceSource.MANUAL,
                                c.ownerUuid(),
                                ""),
                        c.level().getGameTime());
    }

    @GameTest(maxTicks = 400)
    public void placeProtected(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.HONEYCOMB_BLOCK);
        CompanionEntity c = companion(h);
        housePlace(h, c, "guarded house", 5, 2, 5, 2);
        BreakTask inside = breakIn(c, h.absolutePos(new BlockPos(5, 2, 5)), true);
        h.succeedWhen(() -> {
            h.assertTrue(
                    inside.last == dev.friendai.features.mining.minecraft.mining.BlockBreaker.State.PROTECTED,
                    "free time may not break inside a place: " + inside.last);
            h.assertBlockPresent(Blocks.HONEYCOMB_BLOCK, 5, 2, 5);
        });
    }

    @GameTest(maxTicks = 400)
    public void ownerMayBreakInsideAPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.MUD_BRICKS);
        CompanionEntity c = companion(h);
        housePlace(h, c, "owner house", 5, 2, 5, 2);
        BreakTask owner = breakIn(c, h.absolutePos(new BlockPos(5, 2, 5)), false);
        h.succeedWhen(() -> {
            h.assertTrue(
                    owner.last == dev.friendai.features.mining.minecraft.mining.BlockBreaker.State.BROKEN,
                    "an owner order still breaks it: " + owner.last);
        });
    }

    @GameTest(maxTicks = 400)
    public void freeTimeBreaksOutsidePlaces(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(11, 2, 11, Blocks.PACKED_MUD);
        CompanionEntity c = companion(h);
        housePlace(h, c, "far house", 3, 2, 3, 2);
        BreakTask outside = breakIn(c, h.absolutePos(new BlockPos(11, 2, 11)), true);
        h.succeedWhen(() -> h.assertTrue(
                outside.last == dev.friendai.features.mining.minecraft.mining.BlockBreaker.State.BROKEN,
                "free time breaks outside places: " + outside.last));
    }

    @GameTest(maxTicks = 400)
    public void takeOutsidePlaceRefused(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(10, 2, 10, Blocks.CHEST);
        chest(h, 10, 2, 10).setItem(0, new ItemStack(Items.AMETHYST_SHARD, 4));
        UUID owner = UUID.randomUUID();
        CompanionEntity c = registered(h, owner);
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        BlockPos at = h.absolutePos(new BlockPos(10, 2, 10));
        shiftPlan(
                h,
                c,
                "[{\"tool\":\"take_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:amethyst_shard\\\","
                        + "\\\"count\\\":4,\\\"x\\\":" + at.getX() + ",\\\"y\\\":" + at.getY() + ",\\\"z\\\":"
                        + at.getZ()
                        + "}\"}]");
        h.succeedWhen(() -> {
            var state = driver(h).state(owner);
            h.assertTrue(state.shift() == null && !state.history().isEmpty(), "the shift ends: " + state);
            h.assertTrue(
                    state.history().get(0).summary().contains("outside known places"),
                    "summary: " + state.history().get(0).summary());
            h.assertValueEqual(count(chest(h, 10, 2, 10), Items.AMETHYST_SHARD), 4, "nothing taken");
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 100)
    public void command(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setGatewayOverride(
                player.getUUID(),
                (system, messages, tools) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("no model in tests")));
        registered(h, player.getUUID());
        var commands = h.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(player.createCommandSourceStack(), "friendai freetime on chop some wood");
        var on = driver(h).state(player.getUUID());
        h.assertTrue(on.on(), "the command turns free time on");
        h.assertValueEqual(on.origin(), dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "origin");
        h.assertValueEqual(on.wish(), "chop some wood", "wish");
        commands.performPrefixedCommand(player.createCommandSourceStack(), "friendai freetime off");
        h.assertTrue(!driver(h).state(player.getUUID()).on(), "the command turns free time off");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void stopCommandRecordsNoLesson(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setGatewayOverride(
                player.getUUID(),
                (system, messages, tools) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("no model in tests")));
        CompanionEntity c = registered(h, player.getUUID());
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        shiftPlan(h, c, "[{\"tool\":\"stay\"}]");
        c.executionCoordinator().tick();
        h.getLevel()
                .getServer()
                .getCommands()
                .performPrefixedCommand(player.createCommandSourceStack(), "friendai stop");
        var state = driver(h).state(player.getUUID());
        h.assertTrue(!state.on(), "stop ends free time");
        h.assertTrue(state.lessons().isEmpty(), "an owner's stop teaches nothing: " + state.lessons());
        h.assertTrue(state.cooldownUntil().isEmpty(), "an owner's stop is no failure: " + state.cooldownUntil());
        h.assertValueEqual(state.history().get(0).summary(), "stopped by command", "record");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void directorTurnRefusesOtherTools(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        h.succeedWhen(() -> {
            h.assertTrue(driver(h).state(owner).awaitingChoice(), "the director is asked to choose");
            h.assertTrue(driver(h).refusalDuringChoice(owner, "attack_player").isPresent(), "attack is refused");
            h.assertTrue(driver(h).refusalDuringChoice(owner, "mine_blocks").isPresent(), "direct work is refused");
            h.assertTrue(driver(h).refusalDuringChoice(owner, "choose_activity").isEmpty(), "the choice is allowed");
            h.assertTrue(driver(h).refusalDuringChoice(owner, "look_around").isEmpty(), "looking is allowed");
            driver(h).ownerActivity(owner, System.currentTimeMillis());
            h.assertTrue(
                    driver(h).refusalDuringChoice(owner, "mine_blocks").isEmpty(),
                    "once the owner speaks, their orders are not limited");
            driver(h).stop(owner, "test over");
        });
    }

    /** Answers with one tool call, then one reply; records every request so the tool result can be read back. */
    private static final class ToolScript implements LlmGateway {
        private final Queue<LlmResponse> responses = new ArrayDeque<>();
        final List<List<Message>> requests = Collections.synchronizedList(new ArrayList<>());

        ToolScript(String tool, String json) {
            responses.add(new LlmResponse(
                    "",
                    List.of(new ToolCall(
                            "call_1",
                            tool,
                            com.google.gson.JsonParser.parseString(json).getAsJsonObject(),
                            json,
                            null)),
                    StopReason.TOOL_USE,
                    new Usage(0, 0)));
            responses.add(new LlmResponse("Right here.", List.of(), StopReason.END, new Usage(0, 0)));
        }

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new ArrayList<>(messages));
            LlmResponse next = responses.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("script exhausted"))
                    : CompletableFuture.completedFuture(next);
        }

        String transcript() {
            StringBuilder sb = new StringBuilder();
            for (List<Message> request : requests) {
                for (Message message : request) {
                    sb.append(message.text()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * Free time on: between activities the companion stands where it is. The owner walking away only grows the
     * distance — no default follow starts and the companion does not move.
     */
    @GameTest(maxTicks = 400)
    public void freeTimeStandsInsteadOfFollowing(GameTestHelper h) {
        GameTestSupport.platform(h, 24);
        var player = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setGatewayOverride(
                player.getUUID(),
                (system, messages, tools) ->
                        CompletableFuture.failedFuture(new IllegalStateException("no model in tests")));
        CompanionEntity c = registered(h, player.getUUID());
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> true, c::mayFollowOwner);
        var origin = c.position();
        boolean[] moved = {false};
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            ticks[0]++;
            h.assertTrue(
                    runner.current().isEmpty(),
                    "free time must not start the default follow: " + runner.currentToolName());
            h.assertTrue(ticks[0] >= 20, "wait before the owner walks away");
            if (!moved[0]) {
                player.teleportTo(player.getX() + 24.0, player.getY(), player.getZ());
                moved[0] = true;
            }
            h.assertTrue(ticks[0] >= 40, "wait after the owner walked away");
            h.assertTrue(c.distanceToSqr(player) > 400.0, "the owner is out of reach: " + c.distanceToSqr(player));
            h.assertTrue(c.position().distanceToSqr(origin) < 0.25, "the companion stood still");
            driver(h).stop(c.ownerUuid(), "test over");
        });
    }

    /** The same runner with free time off still follows: the gate narrows the old default, it does not replace it. */
    @GameTest(maxTicks = 200)
    public void followWithoutFreeTimeStillFollows(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        h.assertTrue(!driver(h).state(c.ownerUuid()).on(), "free time is off");
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> true, c::mayFollowOwner);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(runner.currentIsDefaultActivity(), "the default follow still starts");
            h.assertValueEqual(runner.currentToolName(), "follow", "a quiet follow");
        });
        runner.stop("test over");
    }

    /** A command wish becomes the shift's plan at once, without asking the director first. */
    @GameTest(maxTicks = 400)
    public void wishCommandQueuesAPlan(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = onlineCompanion(h);
        UUID owner = c.ownerUuid();
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        brains.setPlannerOverride(owner, input -> CompletableFuture.completedFuture(jumpPlan()));
        var state = driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "practise jumping");
        h.assertTrue(!state.awaitingChoice(), "the wish does not wait for the director");
        h.assertTrue(state.shift() != null, "the wish is a shift");
        h.assertValueEqual(state.shift().goal(), "practise jumping", "the shift is the wish");
        h.succeedWhen(() -> {
            var linked = driver(h).state(owner);
            h.assertTrue(linked.shift() != null && linked.shift().planId() != 0, "the plan is linked: " + linked);
            h.assertTrue(
                    c.executionCoordinator()
                            .queue()
                            .plan(linked.shift().planId())
                            .isPresent(),
                    "the plan is queued");
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 200)
    public void failingPlannerTellsOwnerOneLocalizedLine(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        MockOwner owner = mockOwner(h);
        drainSystemMessages(owner.channel());
        CompanionEntity companion = registered(h, owner.player().getUUID());
        var brains = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .sessions();
        h.assertTrue(
                dev.friendai.agent.autonomy.ReflexPlans.fallback(dev.friendai.agent.autonomy.NeedKind.FOOD, null)
                        .isPresent(),
                "FOOD has a deterministic fallback template");
        brains.setGatewayOverride(
                owner.player().getUUID(),
                (system, messages, tools) -> CompletableFuture.completedFuture(
                        new LlmResponse("", List.of(), StopReason.END, new Usage(0, 0))));
        CompletableFuture<dev.friendai.agent.plan.PlanProposal> pending = new CompletableFuture<>();
        brains.setPlannerOverride(owner.player().getUUID(), input -> pending);
        var started =
                driver(h).start(companion, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "make a small camp");
        h.assertTrue(started.shift() != null, "the owner wish starts a shift");
        h.assertValueEqual(started.shift().need(), dev.friendai.agent.autonomy.NeedKind.FOOD, "the selected need");
        pending.completeExceptionally(
                new dev.friendai.agent.plan.PlannerException(new dev.friendai.agent.plan.PlanFailure(
                        dev.friendai.agent.plan.PlanFailure.Code.BROKEN_JSON, "private parser detail", 2)));
        h.succeedWhen(() -> {
            List<Component> messages = drainSystemMessages(owner.channel());
            h.assertTrue(!messages.isEmpty(), "the owner receives the planning result");
            h.assertValueEqual(messages.size(), 1, "one line is sent");
            Component line = messages.getFirst();
            h.assertTrue(
                    containsTranslation(line, "friendai.freetime.no_plan.broken_json"),
                    "the line uses the failure translation: " + line);
            h.assertTrue(!line.getString().contains("Exception"), "no exception type leaks: " + line);
            h.assertTrue(!line.getString().contains("private parser detail"), "parser detail stays private: " + line);
            h.assertTrue(driver(h).state(owner.player().getUUID()).shift() == null, "the failed shift ends");
            h.assertValueEqual(companion.executionCoordinator().queue().planCount(), 0, "no fallback plan is guessed");
            h.succeed();
        });
    }

    /** A chat turn during a shift that reaches for the owner is refused, and the work keeps running. */
    @GameTest(maxTicks = 3600)
    public void approachRefusedDuringShift(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        UUID owner = player.getUUID();
        var server = h.getLevel().getServer();
        var brains = dev.friendai.core.FriendaiServerState.get(server).sessions();
        ToolScript script = new ToolScript("approach", "{\"target\":\"owner\"}");
        brains.setGatewayOverride(owner, script);
        CompanionEntity c = registered(h, owner);
        dev.friendai.core.FriendaiServerState.get(server)
                .freeTime()
                .start(owner, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "", System.currentTimeMillis());
        shiftPlan(h, c, "[{\"tool\":\"stay\"}]");
        h.assertTrue(
                dev.friendai.server.agent.FreeTimeDriver.DENIED.contains("follow_player"),
                "a free-time plan can never queue the follow task");
        brains.post(owner, new BrainEvent.PlayerSaid(owner, "owner", "come here", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(script.requests.size() >= 2, "the refused call is answered: " + script.requests.size());
            String transcript = script.transcript();
            h.assertTrue(transcript.contains("stay where you are"), "the refusal names the reason: " + transcript);
            h.assertTrue(driver(h).state(owner).shift() != null, "the shift survives the message");
            h.assertTrue(!"approach".equals(c.executionCoordinator().currentToolName()), "no approach started");
            h.assertTrue(driver(h).refusalDuringShift(owner, "approach").isPresent(), "still refused");
            driver(h).stop(owner, "test over");
            h.assertTrue(
                    driver(h).refusalDuringShift(owner, "approach").isEmpty(),
                    "with free time off the owner may be approached");
        });
    }

    @GameTest(maxTicks = 200)
    public void foreignPlanIsNotAdopted(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        UUID owner = UUID.randomUUID();
        CompanionEntity c = registered(h, owner);
        var builtins = dev.friendai.tool.builtin.FriendaiTools.build(
                () -> 16, dev.friendai.tool.builtin.MemoryAccess.DISABLED);
        var caller = new dev.friendai.execution.tool.Caller(owner, "", dev.friendai.execution.tool.AccessLevel.OWNER);
        dev.friendai.tool.builtin.FriendaiTools.addPlan(
                c,
                caller,
                com.google.gson.JsonParser.parseString("{\"goal\":\"owner work\",\"steps\":[{\"tool\":\"stay\"}]}")
                        .getAsJsonObject(),
                builtins.taskTools(),
                true,
                Set.of());
        long ownerPlan = c.executionCoordinator().queue().plans().getFirst().id();
        driver(h).start(c, dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
        // A shift saved before a death or a rollback points at a plan id the new queue reused.
        var service = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .freeTime();
        service.beginShift(
                owner,
                "old shift",
                java.util.List.of(),
                "",
                dev.friendai.agent.autonomy.NeedKind.EXPLORE,
                true,
                0,
                Long.MAX_VALUE);
        service.linkPlan(owner, ownerPlan);
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            var state = driver(h).state(owner);
            h.assertTrue(state.shift() == null, "the stale shift is closed: " + state.shift());
            h.assertTrue(!c.executionCoordinator().autonomousActive(), "the owner's plan is not free time's");
            h.assertTrue(c.executionCoordinator().queue().plan(ownerPlan).isPresent(), "the owner's plan is untouched");
            driver(h).stop(owner, "test over");
        });
    }

    @GameTest(maxTicks = 600)
    public void depositStorageFull(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        h.setBlock(6, 2, 9, Blocks.CHEST);
        for (int[] at : new int[][] {{6, 2, 6}, {6, 2, 9}}) {
            Container full = chest(h, at[0], at[1], at[2]);
            for (int i = 0; i < full.getContainerSize(); i++) {
                full.setItem(i, new ItemStack(Items.GRAVEL, 64));
            }
        }
        CompanionEntity c = companion(h);
        storagePlace(h, c, "full chests", 6, 2, 7, 3);
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 10));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new DepositTask("full chests", AutonomySettings.DEFAULT_KEEP), "deposit_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "storage is full", "summary");
            h.assertValueEqual(bag(c, "minecraft:raw_gold"), 10, "nothing moved");
        });
    }
}
