package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.acquisition.ObtainPlanner;
import dev.friendai.features.acquisition.Source;
import dev.friendai.features.acquisition.ToolKind;
import dev.friendai.features.acquisition.ToolNeed;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.search.DropIndex;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.acquisition.minecraft.task.BatchObtainTask;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.acquisition.minecraft.task.StackSpace;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.features.exploration.minecraft.task.ExploreTask;
import dev.friendai.features.inventory.minecraft.task.CheckContainerTask;
import dev.friendai.features.inventory.minecraft.task.GiveItemsTask;
import dev.friendai.foundation.item.Tier;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.BlockMatcher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

public class ObtainTests {
    private static final List<String> STONE_TOOL_IDS = List.of(
            "minecraft:stone_pickaxe", "minecraft:stone_axe", "minecraft:stone_shovel", "minecraft:stone_sword");
    private static final List<Item> STONE_TOOLS =
            List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD);

    private static final class KitOrderGateway implements LlmGateway {
        private final JsonObject plan;
        private int requests;
        private final List<String> requestLastUserLines = new java.util.ArrayList<>();
        private final List<Integer> requestToolResultCounts = new java.util.ArrayList<>();

        KitOrderGateway(JsonObject plan) {
            this.plan = plan.deepCopy();
        }

        @Override
        public synchronized CompletableFuture<LlmResponse> complete(
                String system, List<Message> messages, List<ToolSpec> tools) {
            requests++;
            requestLastUserLines.add(messages.stream()
                    .filter(message -> message.role() == dev.friendai.agent.llm.model.Role.USER)
                    .reduce((first, second) -> second)
                    .map(Message::text)
                    .orElse("<no user line>")
                    .replace('\n', ' '));
            requestToolResultCounts.add((int) messages.stream()
                    .filter(message -> message.role() == dev.friendai.agent.llm.model.Role.TOOL)
                    .count());
            if (requests == 1) {
                String json = plan.toString();
                return CompletableFuture.completedFuture(new LlmResponse(
                        "",
                        List.of(new ToolCall("resource-kit-plan", "queue_add", plan.deepCopy(), json, null)),
                        StopReason.TOOL_USE,
                        new Usage(0, 0)));
            }
            return CompletableFuture.completedFuture(
                    new LlmResponse("The tools are ready.", List.of(), StopReason.END, new Usage(0, 0)));
        }

        synchronized int requests() {
            return requests;
        }

        synchronized List<String> requestLastUserLines() {
            return List.copyOf(requestLastUserLines);
        }

        synchronized List<Integer> requestToolResultCounts() {
            return List.copyOf(requestToolResultCounts);
        }
    }

    private static JsonObject scriptedKitPlan() {
        JsonArray items = new JsonArray();
        for (String item : STONE_TOOL_IDS) {
            JsonObject target = new JsonObject();
            target.addProperty("item", item);
            target.addProperty("count", 2);
            items.add(target);
        }
        JsonObject batchArgs = new JsonObject();
        batchArgs.add("items", items);
        batchArgs.addProperty("use_containers", false);
        batchArgs.addProperty("explore", false);
        batchArgs.addProperty("scope", "local");
        JsonArray steps = new JsonArray();
        steps.add(planStep("obtain_items", batchArgs));
        for (String item : STONE_TOOL_IDS) {
            JsonObject transferArgs = new JsonObject();
            transferArgs.addProperty("item", item);
            transferArgs.addProperty("count", 1);
            transferArgs.addProperty("retain", 1);
            transferArgs.addProperty("strict", true);
            steps.add(planStep("give_items", transferArgs));
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("goal", "make one stone tool set for each of us");
        plan.add("steps", steps);
        return plan;
    }

    private static JsonObject planStep(String tool, JsonObject args) {
        JsonObject step = new JsonObject();
        step.addProperty("tool", tool);
        step.addProperty("args", args.toString());
        return step;
    }

    @GameTest(
            maxTicks = 16000,
            environment = "friendai-gametest:obtain_scripted_kit",
            structure = GameTestArenas.ARENA_32)
    public void scriptedForestKitUsesOneBatchAndFourStrictTransfers(GameTestHelper h) {
        ResourceWorkFixture.BirchKit fixture = ResourceWorkFixture.birchKit(h, 80, 2);
        ResourceWorkFixture.removeDeliveryObstruction(h, fixture);
        CompanionEntity companion = fixture.companion();
        var owner = fixture.owner();
        h.setBlock(fixture.stationSpot(), Blocks.CRAFTING_TABLE);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        companion.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.WOODEN_AXE));
        h.assertTrue(companion.inventory().isEmpty(), "the resource order starts with an empty bag");
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(fixture.stationSpot())),
                "the crafting station should be visible before the batch starts");
        List<BlockPos> unobservedLogs = fixture.allTreeLogs().stream()
                .filter(log -> !GameTestSupport.observeVisible(h, companion, h.absolutePos(log)))
                .toList();
        fixture.allTreeLeaves().forEach(leaf -> GameTestSupport.observeVisible(h, companion, h.absolutePos(leaf)));
        int knownLogs = WorldScan.scan(h.getLevel(), companion, WorldScan.BLOCK_RADIUS, false, false)
                .blocksNearby()
                .getOrDefault("minecraft:birch_log", 0);
        int knownLeaves = WorldScan.scan(h.getLevel(), companion, WorldScan.BLOCK_RADIUS, false, false)
                .blocksNearby()
                .getOrDefault("minecraft:birch_leaves", 0);
        h.assertTrue(
                knownLogs >= 2,
                "the batch needs two observed birch logs for 14 sticks; known=" + knownLogs + ", unobserved="
                        + unobservedLogs);
        h.assertTrue(knownLeaves > 0, "the forest search needs observed birch leaves to classify the trees");
        BlockPos spawn = companion.blockPosition();
        BlockPos stoneView = h.absolutePos(
                new BlockPos(9, fixture.trees().getFirst().getFirst().getY(), 8));
        companion.setPos(stoneView.getX() + 0.5, stoneView.getY(), stoneView.getZ() + 0.5);
        List<BlockPos> unobservedStone = fixture.stoneSource().stream()
                .filter(stone -> !GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)))
                .toList();
        int knownStoneBlocks = WorldScan.scan(h.getLevel(), companion, WorldScan.BLOCK_RADIUS, false, false)
                .blocksNearby()
                .getOrDefault("minecraft:stone", 0);
        companion.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        h.assertTrue(
                knownStoneBlocks >= 18,
                "the forest work area needs 18 visible stone blocks; known=" + knownStoneBlocks + ", unobserved="
                        + unobservedStone);

        List<CraftPlanner.Target> targets = STONE_TOOL_IDS.stream()
                .map(item -> new CraftPlanner.Target(item, 2))
                .toList();
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(
                targets, Map.of(), RecipeIndex.of(h.getLevel()), Stations.available(h.getLevel(), companion));
        CraftPlanner.RawNeed stoneNeed = demand.stream()
                .filter(need -> need.candidates().contains("minecraft:cobblestone"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("stone-tool demand has no cobblestone candidate: " + demand));
        CraftPlanner.RawNeed stickNeed = demand.stream()
                .filter(need -> need.candidates().contains("minecraft:stick"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("stone-tool demand has no stick candidate: " + demand));
        h.assertValueEqual(
                stoneNeed.count(), 18, "stone-tool demand can be satisfied with 18 cobblestone, excluding bootstrap");
        h.assertValueEqual(stickNeed.count(), 14, "stone-tool demand is 14 sticks, excluding bootstrap");
        h.assertTrue(
                demand.stream().noneMatch(CraftPlanner.RawNeed::stationNeed),
                "the visible table needs no extra material");
        WorldFacts observedFacts = WorldScan.scan(h.getLevel(), companion, WorldScan.BLOCK_RADIUS, false, false);
        var stickSourcePlan = ObtainPlanner.plan(
                "minecraft:stick",
                14,
                Map.of(),
                RecipeIndex.of(h.getLevel()),
                DropIndex.of(h.getLevel()),
                observedFacts);
        h.assertTrue(
                stickSourcePlan.actionable(),
                "the visible forest should make 14 sticks obtainable: " + stickSourcePlan);

        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        KitOrderGateway gateway = new KitOrderGateway(scriptedKitPlan());
        runtime.sessions().setGatewayOverride(owner.getUUID(), gateway);
        long startedAt = h.getLevel().getGameTime();
        long[] lastObservationRefresh = {startedAt};
        int[] birchLogsPickedUp = {0};
        int[] lastBirchLogCount = {companion.inventory().countItem(Items.BIRCH_LOG)};
        runtime.sessions()
                .post(
                        owner.getUUID(),
                        new BrainEvent.PlayerSaid(
                                owner.getUUID(),
                                owner.getName().getString(),
                                "Make one stone tool set for you and me",
                                BrainEvent.Source.CHAT));

        int[] firstPickupTick = {-1};
        int[] archivedAtTick = {-1};
        TaskTrace trace = TaskTrace.of(h, companion.executionCoordinator(), companion);
        trace.succeedWhen(() -> {
            long now = h.getLevel().getGameTime();
            int currentBirchLogCount = companion.inventory().countItem(Items.BIRCH_LOG);
            birchLogsPickedUp[0] += Math.max(0, currentBirchLogCount - lastBirchLogCount[0]);
            lastBirchLogCount[0] = currentBirchLogCount;
            if (now - lastObservationRefresh[0] >= 100) {
                lastObservationRefresh[0] = now;
                GameTestSupport.observeVisible(h, companion, h.absolutePos(fixture.stationSpot()));
                fixture.allTreeLogs().forEach(log -> GameTestSupport.observeVisible(h, companion, h.absolutePos(log)));
                fixture.allTreeLeaves()
                        .forEach(leaf -> GameTestSupport.observeVisible(h, companion, h.absolutePos(leaf)));
                fixture.stoneSource()
                        .forEach(stone -> GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)));
            }
            for (ItemEntity drop :
                    h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(32))) {
                if (STONE_TOOLS.stream().anyMatch(drop.getItem()::is)) {
                    drop.setPickUpDelay(0);
                    drop.playerTouch(owner);
                }
            }

            boolean receivedAll = true;
            for (Item tool : STONE_TOOLS) {
                int received = owner.getInventory().countItem(tool);
                h.assertTrue(received <= 1, "owner received a duplicate " + tool + ": " + received);
                if (received == 1 && firstPickupTick[0] < 0) {
                    firstPickupTick[0] = (int) (h.getLevel().getGameTime() - startedAt);
                }
                receivedAll &= received == 1;
            }

            var archive = companion.executionCoordinator().queue().archives().stream()
                    .filter(entry -> entry.goal().equals("make one stone tool set for each of us"))
                    .findFirst();
            if (archive.isPresent() && archivedAtTick[0] < 0) {
                archivedAtTick[0] = (int) h.getLevel().getGameTime();
            }
            if (archive.filter(entry -> !entry.success()).isPresent()) {
                throw new IllegalStateException(
                        "the queued kit failed: " + archive.get().summary());
            }
            int carriedTools = STONE_TOOLS.stream()
                    .mapToInt(companion.inventory()::countItem)
                    .sum();
            if (companion.executionCoordinator().current().isPresent()
                    && companion.inventory().countItem(Items.COBBLESTONE) > 0
                    && carriedTools == 0) {
                h.assertTrue(
                        companion.materialReserve().workReserved("minecraft:cobblestone") >= stoneNeed.count(),
                        "the batch must reserve recipe cobblestone while gathering wood; reserve="
                                + companion.materialReserve());
            }
            boolean stoppedWithoutArchive = gateway.requests() >= 2
                    && h.getLevel().getGameTime() - startedAt > 40
                    && companion.executionCoordinator().current().isEmpty()
                    && archive.isEmpty();
            if (stoppedWithoutArchive) {
                long remainingStoneBlocks = fixture.stoneSource().stream()
                        .filter(pos -> h.getBlockState(pos).is(Blocks.STONE))
                        .count();
                throw new IllegalStateException("the kit task stopped without an archive: requests="
                        + gateway.requests()
                        + ", plans="
                        + companion.executionCoordinator().queue().plans().stream()
                                .map(plan -> plan.id() + ":" + plan.cursor())
                                .toList()
                        + ", jobs="
                        + companion.executionCoordinator().queue().jobs().stream()
                                .map(job -> job.id() + ":" + job.tool() + "=" + job.state() + ":" + job.result())
                                .toList()
                        + ", bag={cobblestone=" + companion.inventory().countItem(Items.COBBLESTONE)
                        + ", sticks=" + companion.inventory().countItem(Items.STICK)
                        + ", stonePickaxes=" + companion.inventory().countItem(Items.STONE_PICKAXE)
                        + ", stoneAxes=" + companion.inventory().countItem(Items.STONE_AXE)
                        + ", stoneShovels=" + companion.inventory().countItem(Items.STONE_SHOVEL)
                        + ", stoneSwords=" + companion.inventory().countItem(Items.STONE_SWORD) + "}"
                        + ", hands={main=" + companion.getMainHandItem() + ", off=" + companion.getOffhandItem()
                        + "}"
                        + ", remainingStoneBlocks=" + remainingStoneBlocks
                        + ", owner=" + owner.blockPosition()
                        + ", companion=" + companion.blockPosition()
                        + ", trace=" + trace.digest(30));
            }
            if (receivedAll && archive.isPresent()) {
                h.assertTrue(
                        archive.get().success(),
                        "the queued kit failed: " + archive.get().summary());
                h.assertValueEqual(archive.get().stepCount(), 5, "one batch and four transfers");
                for (Item tool : STONE_TOOLS) {
                    h.assertValueEqual(companion.inventory().countItem(tool), 1, "one retained " + tool);
                    h.assertValueEqual(owner.getInventory().countItem(tool), 1, "one owner pickup of " + tool);
                }
                List<String> requestLines = gateway.requestLastUserLines();
                List<Integer> toolResultCounts = gateway.requestToolResultCounts();
                // A fast plan can finish before the tool-result reply is composed: the queue_add result and the
                // plan-finished event then share one request, so the last reply is either the second or the third.
                if ((gateway.requests() != 2 && gateway.requests() != 3)
                        || toolResultCounts.size() != gateway.requests()
                        || toolResultCounts.getLast() != 1
                        || !requestLines.getLast().startsWith("[event] plan #")) {
                    throw new IllegalStateException("expected one plan call, the queue_add result and one final "
                            + "plan event in the last reply; got requests=" + gateway.requests()
                            + ", toolResults=" + toolResultCounts + ", last user lines=" + requestLines);
                }
                h.assertValueEqual(firstPickupTick[0] >= 0, true, "a delivered tool was actually picked up");
                h.assertTrue(
                        birchLogsPickedUp[0] >= 2,
                        "the forest supplied birch logs for sticks: picked=" + birchLogsPickedUp[0]);
                var stats =
                        runtime.knowledge().book(owner.getUUID()).searchLedger().stats();
                int failedSiteAttempts = stats.kinds().stream()
                        .mapToInt(kind -> stats.counters(kind).misses())
                        .sum();
                h.assertValueEqual(failedSiteAttempts, 0, "the fixture's selected sites required no failed attempts");
                dev.friendai.FriendaiMod.LOGGER.info(
                        "[resource-order] modelRequests={}, failedSiteAttempts={}, birchLogsPickedUp={}, firstVerifiedPickupTicks={}, gatheredTools={}, delivered={}, retained={}, duplicateHandovers={}",
                        gateway.requests(),
                        failedSiteAttempts,
                        birchLogsPickedUp[0],
                        firstPickupTick[0],
                        8,
                        4,
                        4,
                        0);
                h.succeed();
                return;
            }
            if (archivedAtTick[0] >= 0 && h.getLevel().getGameTime() - archivedAtTick[0] > 80) {
                List<String> drops = h
                        .getLevel()
                        .getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(32))
                        .stream()
                        .filter(drop -> STONE_TOOLS.stream().anyMatch(drop.getItem()::is))
                        .map(drop -> drop.getItem() + "x" + drop.getItem().getCount() + "@" + drop.blockPosition())
                        .toList();
                h.fail("the order archived before verified owner pickup: owner=" + owner.blockPosition()
                        + ", companion=" + companion.blockPosition() + ", inventory="
                        + STONE_TOOLS.stream()
                                .map(tool -> tool + "=" + owner.getInventory().countItem(tool))
                                .toList()
                        + ", drops=" + drops);
                return;
            }
            h.assertTrue(
                    receivedAll && archive.isPresent(),
                    "waiting for archive and all owner pickups; received="
                            + STONE_TOOLS.stream()
                                    .map(tool ->
                                            tool + "=" + owner.getInventory().countItem(tool))
                                    .toList()
                            + ", archive="
                            + archive.map(entry -> entry.summary()).orElse("pending"));
            h.assertTrue(
                    gateway.requests() <= 3,
                    "the scripted order made an unexpected number of model requests: " + gateway.requests()
                            + ", last user lines=" + gateway.requestLastUserLines());
            h.assertTrue(
                    h.getLevel().getGameTime() - startedAt < 15900,
                    "the forest order exceeded its bounded fixture time; requests=" + gateway.requests()
                            + ", companion=" + companion.blockPosition()
                            + ", owner=" + owner.blockPosition()
                            + ", queue="
                            + companion.executionCoordinator().queue().plans());
        });
    }

    @GameTest(maxTicks = 100, structure = GameTestArenas.ARENA_32)
    public void birchKitFixtureStartsEmptyWithSafeSources(GameTestHelper h) {
        ResourceWorkFixture.BirchKit fixture = ResourceWorkFixture.birchKit(h);
        CompanionEntity companion = fixture.companion();
        h.assertTrue(companion.inventory().isEmpty(), "the companion should start with an empty bag");
        h.assertTrue(
                h.getLevel()
                                .getServer()
                                .getPlayerList()
                                .getPlayer(fixture.owner().getUUID())
                        == fixture.owner(),
                "the owner should be online for pickup tests");
        h.assertValueEqual(fixture.trees().size(), 2, "birch sources");
        for (List<BlockPos> tree : fixture.trees()) {
            h.assertValueEqual(tree.size(), 5, "logs in a birch tree");
            for (BlockPos log : tree) h.assertTrue(h.getBlockState(log).is(Blocks.BIRCH_LOG), "birch log at " + log);
        }
        h.assertValueEqual(fixture.stoneSource().size(), 18, "safe exposed stone blocks");
        h.assertTrue(
                GameTestSupport.observeVisible(
                        h, companion, h.absolutePos(fixture.stoneSource().getFirst())),
                "the safe stone source should be visible");
        h.assertTrue(h.getBlockState(fixture.stationSpot()).isAir(), "there should be room for a station");
        for (BlockPos obstruction : fixture.deliveryObstruction()) {
            h.assertTrue(h.getBlockState(obstruction).is(Blocks.COBBLESTONE), "delivery obstruction at " + obstruction);
        }
        ResourceWorkFixture.removeDeliveryObstruction(h, fixture);
        for (BlockPos obstruction : fixture.deliveryObstruction()) {
            h.assertTrue(
                    h.getBlockState(obstruction).isAir(), "delivery obstruction should be removable at " + obstruction);
        }
        h.assertValueEqual(
                fixture.owner().blockPosition(), h.absolutePos(fixture.ownerPickup()), "the owner pickup point");
        h.succeed();
    }

    @GameTest(maxTicks = 100, structure = GameTestArenas.ARENA_24)
    public void undergroundReturnFixtureHasAReversibleRouteAndRemovableObstruction(GameTestHelper h) {
        ResourceWorkFixture.UndergroundReturn fixture = ResourceWorkFixture.undergroundReturn(h);
        h.assertValueEqual(fixture.anchor().getY(), fixture.surfaceY() + 1, "the surface return anchor");
        for (BlockPos cell : fixture.routeCells()) {
            h.assertTrue(h.getBlockState(cell).isAir(), "return route cell should be clear at " + cell);
        }
        for (BlockPos support : fixture.routeSupports()) {
            h.assertTrue(h.getBlockState(support).is(Blocks.STONE), "return route support at " + support);
        }
        h.assertTrue(h.getBlockState(fixture.stoneTarget()).is(Blocks.IRON_ORE), "the underground target");
        h.setBlock(fixture.obstruction(), Blocks.STONE);
        h.assertTrue(h.getBlockState(fixture.obstruction()).is(Blocks.STONE), "the return obstruction should be live");
        h.setBlock(fixture.obstruction(), Blocks.AIR);
        h.assertTrue(h.getBlockState(fixture.obstruction()).isAir(), "the return obstruction should be removable");
        h.succeed();
    }

    @GameTest(maxTicks = 1600, structure = GameTestArenas.ARENA_64)
    public void kitDeliveryResumesWithoutRecrafting(GameTestHelper h) {
        int surfaceY = 80;
        ResourceWorkFixture.BirchKit fixture = ResourceWorkFixture.birchKit(h, surfaceY);
        ResourceWorkFixture.removeDeliveryObstruction(h, fixture);
        CompanionEntity companion = fixture.companion();
        var owner = fixture.owner();
        var nearbyOwner = h.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, surfaceY + 1, 2.5));
        owner.teleportTo(nearbyOwner.x, nearbyOwner.y, nearbyOwner.z);
        BlockPos deliveryGate = h.absolutePos(new BlockPos(8, surfaceY + 1, 30));
        h.assertTrue(
                h.getLevel().getBlockState(deliveryGate).isAir(),
                "the second delivery starts behind a closed queue guard");
        companion.inventory().addItem(new ItemStack(Items.STONE_AXE));
        companion.inventory().addItem(new ItemStack(Items.STONE_AXE));
        companion.inventory().addItem(new ItemStack(Items.STONE_SHOVEL));
        companion.inventory().addItem(new ItemStack(Items.STONE_SHOVEL));

        PlanDefinition plan = new PlanDefinition(List.of(
                deliveryAction("minecraft:stone_axe", owner.getUUID()),
                deliveryAction(
                        "minecraft:stone_shovel",
                        owner.getUUID(),
                        List.of(new Guard.BlockAt(
                                "minecraft:gold_block",
                                deliveryGate.getX(),
                                deliveryGate.getY(),
                                deliveryGate.getZ())))));
        ExecutionCoordinator runner = companion.executionCoordinator();
        long planId =
                runner.enqueuePlan("deliver kit", plan, PlanOptions.DEFAULT, Map.of("give_items", (context, step) -> {
                    JsonObject args = step.args();
                    return new GiveItemsTask(
                            ItemMatcher.parse(args.get("item").getAsString()).orElseThrow(),
                            args.get("count").getAsInt(),
                            args.get("retain").getAsInt(),
                            args.get("strict").getAsBoolean());
                }));

        int[] phase = {0};
        int[] ticksAfterRestore = {0};
        boolean[] complete = {false};
        TaskTrace.of(h, runner, companion).succeedWhen(() -> {
            runner.tick();
            if (phase[0] == 0
                    && runner.queue().jobsOfPlan(planId).stream()
                            .anyMatch(
                                    job -> job.args().get("item").getAsString().equals("minecraft:stone_axe")
                                            && job.state() == dev.friendai.execution.queue.JobState.DONE)) {
                ItemEntity axeDrop = h
                        .getLevel()
                        .getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(8))
                        .stream()
                        .filter(drop -> drop.getItem().is(Items.STONE_AXE))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the completed first step left no axe handover"));
                axeDrop.setPickUpDelay(0);
                axeDrop.playerTouch(owner);
                h.assertValueEqual(owner.getInventory().countItem(Items.STONE_AXE), 1, "owner picked up the first axe");
                h.assertValueEqual(companion.inventory().countItem(Items.STONE_AXE), 1, "one axe remains reserved");

                var farOwner = h.absoluteVec(new net.minecraft.world.phys.Vec3(27.5, surfaceY + 1, 4.5));
                owner.teleportTo(farOwner.x, farOwner.y, farOwner.z);
                for (int y = surfaceY + 1; y <= surfaceY + 5; y++) {
                    for (int z = 0; z <= 32; z++) h.setBlock(new BlockPos(16, y, z), Blocks.BEDROCK);
                    for (int x = 0; x <= 32; x++) {
                        h.setBlock(new BlockPos(x, y, 0), Blocks.BEDROCK);
                        h.setBlock(new BlockPos(x, y, 32), Blocks.BEDROCK);
                    }
                    for (int z = 0; z <= 32; z++) {
                        h.setBlock(new BlockPos(0, y, z), Blocks.BEDROCK);
                        h.setBlock(new BlockPos(32, y, z), Blocks.BEDROCK);
                    }
                }
                for (int x = 0; x <= 32; x++) {
                    for (int z = 0; z <= 32; z++) h.setBlock(new BlockPos(x, surfaceY + 6, z), Blocks.BEDROCK);
                }
                h.assertTrue(
                        h.getBlockState(new BlockPos(16, surfaceY + 5, 12)).is(Blocks.BEDROCK),
                        "the delivery barrier reaches above the companion");
                h.assertTrue(
                        h.getBlockState(new BlockPos(4, surfaceY + 6, 4)).is(Blocks.BEDROCK),
                        "the delivery enclosure roof prevents a climb-over route");
                h.assertTrue(
                        companion.blockPosition().getX()
                                < h.absolutePos(new BlockPos(16, surfaceY + 1, 4))
                                        .getX(),
                        "the companion is on the blocked side before the second delivery");
                h.getLevel().setBlock(deliveryGate, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                phase[0] = 1;
            } else if (phase[0] == 1
                    && runner.current().orElse(null) instanceof GiveItemsTask task
                    && task.waitingTicks() >= 20) {
                var cursor = runner.queue().plan(planId).orElseThrow().cursor();
                h.assertTrue(cursor != null, "the structured plan cursor was lost");
                h.assertTrue(
                        cursor.pendingAction() != null
                                && cursor.pendingAction()
                                        .args()
                                        .get("item")
                                        .getAsString()
                                        .equals("minecraft:stone_shovel"),
                        "restore must resume at the second delivery step");
                h.assertValueEqual(
                        runner.queue().jobsOfPlan(planId).stream()
                                .filter(job ->
                                        job.args().get("item").getAsString().equals("minecraft:stone_axe"))
                                .findFirst()
                                .orElseThrow()
                                .state(),
                        dev.friendai.execution.queue.JobState.DONE,
                        "the completed axe step stays done before save");

                TagValueOutput saved = TagValueOutput.createWithContext(
                        ProblemReporter.DISCARDING, h.getLevel().registryAccess());
                companion.saveWithoutId(saved);
                companion.load(TagValueInput.create(
                        ProblemReporter.DISCARDING, h.getLevel().registryAccess(), saved.buildResult()));
                h.assertTrue(
                        runner.current()
                                .map(active -> active.type().equals("give_items"))
                                .orElse(false),
                        "the pending shovel transfer did not restore");
                phase[0] = 2;
            } else if (phase[0] == 2) {
                ticksAfterRestore[0]++;
                h.assertValueEqual(owner.getInventory().countItem(Items.STONE_AXE), 1, "reload did not replay the axe");
                h.assertValueEqual(
                        owner.getInventory().countItem(Items.STONE_SHOVEL), 0, "the blocked shovel was not picked up");
                h.assertValueEqual(
                        companion.inventory().countItem(Items.STONE_SHOVEL), 2, "blocked work kept both shovels");
                h.assertValueEqual(
                        h
                                .getLevel()
                                .getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(8))
                                .stream()
                                .filter(drop -> drop.getItem().is(Items.STONE_SHOVEL))
                                .mapToInt(drop -> drop.getItem().getCount())
                                .sum(),
                        0,
                        "blocked work spawned no shovel handover");
                if (ticksAfterRestore[0] >= 40) {
                    h.setBlock(new BlockPos(16, surfaceY + 1, 4), Blocks.AIR);
                    h.setBlock(new BlockPos(16, surfaceY + 2, 4), Blocks.AIR);
                    phase[0] = 3;
                }
            } else if (phase[0] == 3) {
                var archive = runner.queue().archives().stream()
                        .filter(entry -> entry.id() == planId)
                        .findFirst();
                if (archive.isPresent()) {
                    h.assertTrue(
                            archive.get().success(),
                            "the restored kit plan failed: " + archive.get().summary());
                    ItemEntity shovelDrop = h
                            .getLevel()
                            .getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(8))
                            .stream()
                            .filter(drop -> drop.getItem().is(Items.STONE_SHOVEL))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("the resumed step left no shovel handover"));
                    shovelDrop.setPickUpDelay(0);
                    shovelDrop.playerTouch(owner);
                    h.assertValueEqual(
                            owner.getInventory().countItem(Items.STONE_AXE), 1, "the axe was handed over once");
                    h.assertValueEqual(
                            owner.getInventory().countItem(Items.STONE_SHOVEL), 1, "owner picked up the shovel");
                    h.assertValueEqual(companion.inventory().countItem(Items.STONE_AXE), 1, "the axe reserve remains");
                    h.assertValueEqual(
                            companion.inventory().countItem(Items.STONE_SHOVEL), 1, "the shovel reserve remains");
                    complete[0] = true;
                }
            }
            h.assertTrue(
                    complete[0],
                    "the restored kit plan has not completed without repeating its first transfer"
                            + " (phase=" + phase[0]
                            + ", ticksAfterRestore=" + ticksAfterRestore[0]
                            + ", current="
                            + runner.current().map(task -> task.describe()).orElse("none")
                            + ", companion=" + companion.blockPosition()
                            + ", owner=" + owner.blockPosition()
                            + ", ownerItems=" + owner.getInventory().countItem(Items.STONE_AXE) + "/"
                            + owner.getInventory().countItem(Items.STONE_SHOVEL)
                            + ", carriedItems=" + companion.inventory().countItem(Items.STONE_AXE) + "/"
                            + companion.inventory().countItem(Items.STONE_SHOVEL)
                            + ", jobs="
                            + runner.queue().jobsOfPlan(planId).stream()
                                    .map(job -> job.args().get("item").getAsString() + ":" + job.state())
                                    .toList()
                            + ", gate=" + h.getLevel().getBlockState(deliveryGate) + ")");
        });
    }

    private static PlanNode.Action deliveryAction(String item, java.util.UUID owner) {
        return deliveryAction(item, owner, List.of());
    }

    private static PlanNode.Action deliveryAction(String item, java.util.UUID owner, List<Guard> guards) {
        JsonObject args = new JsonObject();
        args.addProperty("item", item);
        args.addProperty("count", 1);
        args.addProperty("retain", 1);
        args.addProperty("strict", true);
        return new PlanNode.Action(new QueuedStep(
                "give_items", args, null, guards, GuardAction.WAIT, FailurePolicy.ASK, 1, List.of(), owner, false));
    }

    /** The complete caller-aware obtain chain must find face-adjacent stone while the sight queue is full. */
    @GameTest(
            maxTicks = 2400,
            environment = "friendai-gametest:buried_stone_queue_pressure",
            structure = GameTestArenas.ARENA_24)
    public void callerObtainsStonePickaxeFromBuriedStone(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 24);
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 194; y < 199; y++) h.setBlock(x, y, z, Blocks.STONE);
                for (int y = 199; y <= 200; y++) h.setBlock(x, y, z, Blocks.DIRT);
            }
        }
        var owner = GameTestSupport.mockPlayerAt(h, 8, 201, 12);
        // Seed one station
        h.setBlock(8, 201, 10, Blocks.CRAFTING_TABLE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 10, 201, 10);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        companion.inventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
        companion.inventory().setItem(2, new ItemStack(Items.STICK, 2));
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var resources = runtime.resourceCandidates();
        h.assertTrue(
                resources.confirmedRecent(companion, 16).stream()
                        .noneMatch(observation -> observation.blockId().equals("minecraft:stone")),
                "the starting view must contain no stone evidence");
        h.assertValueEqual(0, companion.inventory().countItem(Items.COBBLESTONE), "no cobblestone may be preloaded");
        h.assertValueEqual(0, companion.inventory().countItem(Items.STONE_PICKAXE), "no pickaxe may be preloaded");
        var caller = new dev.friendai.execution.tool.Caller(
                owner.getUUID(), owner.getName().getString(), dev.friendai.execution.tool.AccessLevel.OWNER);
        Result result = new Result();
        ExecutionCoordinator runner = obtain(
                companion,
                result,
                obtainTask("minecraft:stone_pickaxe", 1, false, true, 40, 2200, 64, 128, 800, "local", caller));
        var observer = runtime.observations().observer(companion);
        List<BlockPos> pressureOffsets = new java.util.ArrayList<>();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int dy = -1; dy <= 2; dy++) pressureOffsets.add(new BlockPos(x, dy, z));
            }
        }
        int[] pressureCellIndex = {0};
        boolean[] queueWasFullWhenStoneEvidenceWasPublished = {false};
        boolean[] faceAdjacentStoneWasPublished = {false};
        TaskTrace.of(h, runner, companion).succeedWhen(() -> {
            if (!faceAdjacentStoneWasPublished[0]) {
                int attempts = 0;
                while (observer.queuedJobs() < 512 && attempts < pressureOffsets.size()) {
                    BlockPos offset = pressureOffsets.get(pressureCellIndex[0]);
                    pressureCellIndex[0] = (pressureCellIndex[0] + 1) % pressureOffsets.size();
                    observer.inspect(companion.blockPosition().offset(offset));
                    attempts++;
                }
            }
            int queuedBeforeTaskTick = observer.queuedJobs();
            runner.tick();
            if (hasAdjacentStoneEvidence(resources, companion)) {
                if (!faceAdjacentStoneWasPublished[0])
                    queueWasFullWhenStoneEvidenceWasPublished[0] = queuedBeforeTaskTick == 512;
                faceAdjacentStoneWasPublished[0] = true;
            }
            h.assertTrue(result.status != null, "caller-aware obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary + " at " + companion.blockPosition());
            h.assertTrue(
                    dev.friendai.task.inventory.InventoryOps.count(
                                    companion.inventory(),
                                    dev.friendai.task.inventory.ItemMatcher.parse("minecraft:stone_pickaxe")
                                            .orElseThrow())
                            > 0,
                    "the companion must actually craft a stone pickaxe from the buried stone");
            h.assertTrue(
                    queueWasFullWhenStoneEvidenceWasPublished[0],
                    "the observer queue must be full immediately before adjacent stone evidence is published");
            h.assertTrue(faceAdjacentStoneWasPublished[0], "stone must be processed from the own-break evidence path");
        });
    }

    private static boolean hasAdjacentStoneEvidence(
            dev.friendai.perception.resource.ResourceCandidates resources, CompanionEntity companion) {
        return resources.confirmedRecent(companion, 16).stream()
                .anyMatch(observation -> observation.blockId().equals("minecraft:stone")
                        && observation.source()
                                == dev.friendai.features.knowledge.ResourceObservation.EvidenceSource.ADJACENT_BREAK);
    }

    @GameTest(
            maxTicks = 18000,
            environment = "friendai-gametest:obtain_buried_tool_set",
            structure = GameTestArenas.ARENA_24)
    public void callerObtainsAndDeliversStoneToolSetFromBuriedStone(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 24);
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 194; y < 199; y++) h.setBlock(x, y, z, Blocks.STONE);
                for (int y = 199; y <= 200; y++) h.setBlock(x, y, z, Blocks.DIRT);
            }
        }
        var owner = GameTestSupport.mockPlayerAt(h, 8, 201, 12);
        h.setBlock(8, 201, 10, Blocks.CRAFTING_TABLE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 10, 201, 10);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        companion.inventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
        companion.inventory().setItem(2, new ItemStack(Items.STICK, 2));
        companion.inventory().setItem(3, new ItemStack(Items.OAK_LOG, 8));
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(new BlockPos(8, 201, 10))),
                "crafting table should be visible");
        Caller caller =
                new Caller(owner.getUUID(), owner.getName().getString(), dev.friendai.execution.tool.AccessLevel.OWNER);
        List<String> toolIds = List.of(
                "minecraft:stone_pickaxe", "minecraft:stone_axe", "minecraft:stone_shovel", "minecraft:stone_sword");
        List<Item> tools = List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD);
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 18000, () -> false);
        runner.setEventSink(result);
        runner.start(
                new BatchObtainTask(
                        toolIds.stream()
                                .map(itemId -> new CraftPlanner.Target(itemId, 1))
                                .toList(),
                        false,
                        true,
                        64,
                        17000,
                        64,
                        128,
                        8000,
                        "local",
                        caller,
                        null,
                        new MinecraftObtainSubtaskFactory()),
                "obtain_items");
        int[] phase = {0};
        int[] waitForPickup = {0};
        boolean[] deliveryStarted = {false};
        boolean[] completed = {false};
        TaskTrace.of(h, runner, companion).succeedWhen(() -> {
            if (phase[0] == 0) {
                runner.tick();
                h.assertTrue(result.status != null, "the buried-stone batch should finish");
                h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary + " at " + companion.blockPosition());
                for (Item item : tools) {
                    h.assertValueEqual(companion.inventory().countItem(item), 1, "crafted " + item);
                }
                result.status = null;
                result.summary = null;
                phase[0] = 1;
            } else if (phase[0] <= tools.size()) {
                if (!deliveryStarted[0]) {
                    runner.start(
                            new GiveItemsTask(
                                    ItemMatcher.parse(toolIds.get(phase[0] - 1)).orElseThrow(), 1),
                            "give_items");
                    deliveryStarted[0] = true;
                }
                runner.tick();
                if (result.status != null) {
                    h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary);
                    result.status = null;
                    result.summary = null;
                    phase[0]++;
                    deliveryStarted[0] = false;
                }
            } else {
                waitForPickup[0]++;
                if (waitForPickup[0] >= 12) {
                    AABB aroundOwner = new AABB(owner.blockPosition()).inflate(8);
                    for (ItemEntity drop : h.getLevel().getEntitiesOfClass(ItemEntity.class, aroundOwner)) {
                        if (tools.stream().anyMatch(drop.getItem()::is)) {
                            drop.playerTouch(owner);
                        }
                    }
                }
                boolean receivedAll = true;
                for (Item item : tools) {
                    int received = owner.getInventory().countItem(item);
                    h.assertTrue(received <= 1, "owner received duplicate " + item + ": " + received);
                    receivedAll &= received == 1;
                }
                if (receivedAll) {
                    for (Item item : tools) {
                        h.assertValueEqual(owner.getInventory().countItem(item), 1, "owner received " + item);
                    }
                    completed[0] = true;
                }
            }
            if (phase[0] > tools.size()) {
                h.assertTrue(waitForPickup[0] < 80, "owner did not pick up all delivered tools");
            }
            h.assertTrue(completed[0], "still working through the obtain and delivery sequence");
        });
    }

    @GameTest(maxTicks = 4800, structure = GameTestArenas.ARENA_24)
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

    /**
     * The done-when of the tool-life work: a nearly worn pickaxe plus a compatible spare finish the order, and the
     * plan never sends the companion to make a third tool the bag did not need.
     */
    @GameTest(structure = GameTestArenas.ARENA_24, maxTicks = 4000)
    public void aWornPickaxeAndASpareMineTheWholeOrderWithoutACraft(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 20);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 301, 8);
        companion.setOwner(java.util.UUID.randomUUID());
        for (int y = 301; y <= 302; y++) {
            for (int z = 4; z <= 12; z++) h.setBlock(8, y, z, Blocks.STONE);
        }
        ItemStack worn = new ItemStack(Items.WOODEN_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 3);
        companion.inventory().addItem(worn);
        companion.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        for (int y = 301; y <= 302; y++) {
            for (int z = 4; z <= 12; z++) {
                h.assertTrue(
                        GameTestSupport.observeVisible(h, companion, h.absolutePos(new BlockPos(8, y, z))),
                        "stone should be visible at y " + y + " z " + z);
            }
        }
        Result result = new Result();
        ExecutionCoordinator runner = obtain(companion, result, obtainTask("minecraft:cobblestone", 9, false, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(
                    companion.inventory().countItem(Items.COBBLESTONE) >= 9,
                    "cobblestone: " + companion.inventory().countItem(Items.COBBLESTONE));
            h.assertTrue(
                    pickaxes(companion) <= 2,
                    "the spare is enough: no third pickaxe may be made, have " + pickaxes(companion));
        });
    }

    /**
     * The done-when of the wool work: white wool comes from the farther white sheep, and the nearer black sheep is
     * not shorn for an order it cannot fill.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 2400)
    public void aWhiteWoolOrderShearsTheFartherWhiteSheep(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var black = h.spawn(EntityTypes.SHEEP, 4, 2, 4);
        black.setColor(net.minecraft.world.item.DyeColor.BLACK);
        h.spawn(EntityTypes.SHEEP, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.SHEARS));
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:white_wool", 1, false, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            h.assertTrue(
                    c.inventory().countItem(Items.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE)) >= 1,
                    "white wool: " + c.inventory().countItem(Items.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE)));
            h.assertTrue(black.readyForShearing(), "the nearer black sheep was not shorn: " + result.summary);
        });
    }

    private static int pickaxes(CompanionEntity companion) {
        int count = dev.friendai.task.inventory.InventoryOps.count(
                companion.inventory(),
                ItemMatcher.parse("minecraft:wooden_pickaxe").orElseThrow());
        for (ItemStack stack : List.of(companion.getMainHandItem(), companion.getOffhandItem())) {
            if (stack.is(Items.WOODEN_PICKAXE)) count += stack.getCount();
        }
        return count;
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

    /** How many of the item the companion carries right now. */
    private static int carried(CompanionEntity companion, String itemId) {
        return dev.friendai.task.inventory.InventoryOps.count(
                companion.inventory(), ItemMatcher.parse(itemId).orElseThrow());
    }

    private static ExecutionCoordinator batch(CompanionEntity c, Result result, BatchObtainTask task) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 9600, () -> false);
        runner.setEventSink(result);
        runner.start(task, "obtain_items");
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
        for (int x = 0; x <= 9; x++) {
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

    /** The scan reports what every carried stack can still break, so planning starts from real tool life. */
    @GameTest(structure = GameTestArenas.ARENA_16)
    public void scanReportsTheRemainingLifeOfCarriedTools(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemStack inHand = new ItemStack(Items.STONE_PICKAXE);
        inHand.setDamageValue(10);
        c.setItemSlot(EquipmentSlot.MAINHAND, inHand);
        ItemStack spare = new ItemStack(Items.STONE_PICKAXE);
        spare.setDamageValue(5);
        c.inventory().addItem(spare);
        c.inventory().addItem(new ItemStack(Items.WOODEN_AXE));

        WorldFacts world = WorldScan.scan(h.getLevel(), c, 16, false, false);

        h.assertValueEqual(
                inHand.getMaxDamage() - 10 + spare.getMaxDamage() - 5,
                world.toolBlocks().getOrDefault("minecraft:stone_pickaxe", 0),
                "equipped and carried stacks of one tool add up");
        h.assertValueEqual(
                new ItemStack(Items.WOODEN_AXE).getMaxDamage(),
                world.toolBlocks().getOrDefault("minecraft:wooden_axe", 0),
                "an unused tool counts its whole life");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_32)
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

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void scanWithoutContainers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 8, Blocks.CHEST);

        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.containerItems().isEmpty(), "containers were read: " + world.containerItems());
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void scanOverworldFlag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.dimensionHasOre(), "the overworld has ores");
        h.succeed();
    }

    @GameTest(maxTicks = 100, structure = GameTestArenas.ARENA_16)
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
                                        new dev.friendai.foundation.spatial.Pos(log.getX(), log.getY(), log.getZ())),
                                new dev.friendai.foundation.spatial.Pos(2, 2, 2),
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

    @GameTest(maxTicks = 1200, structure = GameTestArenas.ARENA_32)
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

    @GameTest(maxTicks = 1200, structure = GameTestArenas.ARENA_32)
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

    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_24)
    public void staircaseFindsOre(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        stoneColumn(h, 16);
        BlockPos ore = new BlockPos(3, -6, 10);
        h.setBlock(ore, Blocks.GOLD_ORE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        Result result = new Result();
        ExecutionCoordinator runner = explore(
                c,
                result,
                new ExploreTask(BlockMatcher.parse("minecraft:gold_ore").orElseThrow(), true, -6, 64, 128, 6000));
        h.succeedWhen(() -> {
            if (h.getBlockState(ore).is(Blocks.GOLD_ORE)) GameTestSupport.observeVisible(h, c, h.absolutePos(ore));
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("found minecraft:gold_ore at"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_24)
    public void staircaseAvoidsLava(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        stoneColumn(h, 16);
        h.setBlock(3, -1, 5, Blocks.LAVA);
        h.setBlock(4, -1, 5, Blocks.LAVA);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
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
            for (int x = 0; x <= 9; x++) {
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

    @GameTest(maxTicks = 4800, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
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
     * A dirt cap hides the stone layer, and the search only mines what it can see, so the seams that break the
     * surface are the whole lead: the plan mines those three tops and the layer under them is what makes the pickaxe.
     */
    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
    public void stonePickaxeFromBuriedStone(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -8; y <= -2; y++) h.setBlock(x, y, z, Blocks.STONE);
                for (int y = -1; y <= 1; y++) h.setBlock(x, y, z, Blocks.DIRT);
            }
        }
        BlockPos[] outcrops = {new BlockPos(3, 1, 3), new BlockPos(5, 1, 5), new BlockPos(3, 1, 7)};
        for (BlockPos outcrop : outcrops) h.setBlock(outcrop, Blocks.STONE);
        // The planks let the companion build a station if the seeded one is out of reach, instead of turning the run
        // into a search for a tree.
        BlockPos table = new BlockPos(6, 2, 2);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
        c.inventory().addItem(new ItemStack(Items.STICK, 4));
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "table should be visible");
        for (BlockPos outcrop : outcrops) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(outcrop)),
                    "the seam should be visible at " + outcrop);
        }
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:stone_pickaxe", 1, true, true));
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            GameTestSupport.observeVisible(h, c, h.absolutePos(table));
            for (BlockPos outcrop : outcrops) {
                if (h.getBlockState(outcrop).is(Blocks.STONE)) {
                    GameTestSupport.observeVisible(h, c, h.absolutePos(outcrop));
                }
            }
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.STONE_PICKAXE), 1, "pickaxes");
        });
    }

    /**
     * What the companion has seen ages out of the observation window, and this chain is longer than the oak one, so
     * the test keeps its evidence fresh on every tick the way its sibling does.
     */
    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 4800, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_16)
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
            h.assertFalse(
                    result.summary.contains("work limit"),
                    "a finished search reports the material, not a planning limit: " + result.summary);
        });
    }

    @GameTest(maxTicks = 4800, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 600, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_16)
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

    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
    public void batchObtainsStoneToolSet(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        // The missing cobblestone comes from a chest rather than from mining: this test is about the batch planning
        // one shared material demand and crafting only after it is met, and the mining path has its own tests.
        BlockPos table = new BlockPos(4, 2, 2);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        BlockPos chestPos = new BlockPos(5, 2, 3);
        h.setBlock(chestPos, Blocks.CHEST);
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(chestPos));
        h.assertTrue(chest != null, "the chest has no block entity");
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        chest.setChanged();
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        c.inventory().setItem(1, new ItemStack(Items.STICK, 16));
        c.inventory().setItem(2, new ItemStack(Items.COBBLESTONE, 9));
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should be visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(chestPos)), "chest should be visible");
        var caller =
                new Caller(owner.getUUID(), owner.getName().getString(), dev.friendai.execution.tool.AccessLevel.OWNER);
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 9600, () -> false);
        runner.setEventSink(result);
        runner.start(new CheckContainerTask(h.absolutePos(chestPos)), "check_container");
        BatchObtainTask[] batch = {null};
        // 0: opening the chest, 1: running the batch, 2: done. Every phase that is not the last one fails its check
        // on purpose, because succeedWhen stops the test the first time its body returns without an exception.
        int[] phase = {0};
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            if (phase[0] == 0) {
                runner.tick();
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "the chest was not checked: " + result.status + " " + result.summary);
                result.status = null;
                result.summary = null;
                batch[0] = new BatchObtainTask(
                        List.of(
                                new CraftPlanner.Target("minecraft:stone_pickaxe", 2),
                                new CraftPlanner.Target("minecraft:stone_axe", 2)),
                        true,
                        true,
                        64,
                        9000,
                        64,
                        128,
                        6000,
                        "local",
                        caller,
                        null,
                        new MinecraftObtainSubtaskFactory());
                runner.start(batch[0], "obtain_items");
                phase[0] = 1;
            } else {
                GameTestSupport.observeVisible(h, c, h.absolutePos(table));
                // Gathering the shared material comes before any crafting, so while the chest still holds the
                // cobblestone the batch is short of, none of the set may exist yet.
                if (!chest.getItem(0).isEmpty()) {
                    h.assertValueEqual(
                            c.inventory().countItem(Items.STONE_PICKAXE),
                            0,
                            "the batch crafted before gathering the cobblestone it was short of");
                    h.assertValueEqual(
                            c.inventory().countItem(Items.STONE_AXE),
                            0,
                            "the batch crafted before gathering the cobblestone it was short of");
                }
                runner.tick();
                h.assertTrue(result.status != null, "the batch should finish");
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        result.summary + " table " + h.absolutePos(table) + " " + h.getBlockState(table) + " at "
                                + c.blockPosition());
                h.assertValueEqual(c.inventory().countItem(Items.STONE_PICKAXE), 2, "stone pickaxes");
                h.assertValueEqual(c.inventory().countItem(Items.STONE_AXE), 2, "stone axes");
                h.assertTrue(chest.getItem(0).isEmpty(), "the batch never took the cobblestone it was short of");
                h.assertTrue(
                        result.summary.startsWith("got 2 minecraft:stone_pickaxe, 2 minecraft:stone_axe in "),
                        result.summary);
                h.assertValueEqual(
                        batch[0].workResult().orElseThrow().lines(),
                        List.of(
                                new TaskWorkResult.Line(
                                        "minecraft:stone_pickaxe", 2, 2, TaskWorkResult.Evidence.CARRIED),
                                new TaskWorkResult.Line("minecraft:stone_axe", 2, 2, TaskWorkResult.Evidence.CARRIED)),
                        "verified batch work");
                phase[0] = 2;
            }
            h.assertTrue(phase[0] == 2, "still working");
        });
    }

    /**
     * A stone-tool batch whose stone is split across two visible sources takes from both. The need accepts any member
     * of the stone-tool material family, so a source that covers only part of the deficit contributes its part instead
     * of being skipped, and blackstone, which is nowhere here, is never asked for.
     */
    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
    public void aBatchSplitsItsStoneDemandAcrossAccessibleSources(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        // The chests are the only stone material in reach: a stone floor would let the companion mine one unit
        // itself, and the split between the two chests would depend on what sight happened to record.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 1, z, Blocks.DIRT);
            }
        }
        BlockPos table = new BlockPos(4, 2, 2);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        BlockPos firstPos = new BlockPos(5, 2, 3);
        BlockPos secondPos = new BlockPos(5, 2, 5);
        h.setBlock(firstPos, Blocks.CHEST);
        h.setBlock(secondPos, Blocks.CHEST);
        Container first = (Container) h.getLevel().getBlockEntity(h.absolutePos(firstPos));
        Container second = (Container) h.getLevel().getBlockEntity(h.absolutePos(secondPos));
        h.assertTrue(first != null, "the first chest has no block entity");
        h.assertTrue(second != null, "the second chest has no block entity");
        first.setItem(0, new ItemStack(Items.COBBLED_DEEPSLATE, 6));
        second.setItem(0, new ItemStack(Items.COBBLESTONE, 6));
        first.setChanged();
        second.setChanged();
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        c.inventory().setItem(1, new ItemStack(Items.STICK, 16));
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(table)), "crafting table should be visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(firstPos)), "first chest should be visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(secondPos)), "second chest should be visible");
        var caller =
                new Caller(owner.getUUID(), owner.getName().getString(), dev.friendai.execution.tool.AccessLevel.OWNER);
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 9600, () -> false);
        runner.setEventSink(result);
        runner.start(new CheckContainerTask(h.absolutePos(firstPos)), "check_container");
        BatchObtainTask[] batch = {null};
        // 0: first chest, 1: second chest, 2: the batch. Every phase before the last one throws on purpose,
        // because succeedWhen stops the test the first time its body returns without an exception.
        int[] phase = {0};
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            if (phase[0] == 0) {
                runner.tick();
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "the first chest was not checked: " + result.status + " " + result.summary);
                result.status = null;
                result.summary = null;
                runner.start(new CheckContainerTask(h.absolutePos(secondPos)), "check_container");
                phase[0] = 1;
                h.assertTrue(false, "the first chest is checked");
                return;
            }
            if (phase[0] == 1) {
                runner.tick();
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "the second chest was not checked: " + result.status + " " + result.summary);
                result.status = null;
                result.summary = null;
                batch[0] = new BatchObtainTask(
                        List.of(
                                new CraftPlanner.Target("minecraft:stone_pickaxe", 2),
                                new CraftPlanner.Target("minecraft:stone_axe", 2)),
                        true,
                        false,
                        64,
                        9000,
                        64,
                        128,
                        6000,
                        "local",
                        caller,
                        null,
                        new MinecraftObtainSubtaskFactory());
                runner.start(batch[0], "obtain_items");
                phase[0] = 2;
                h.assertTrue(false, "the second chest is checked");
                return;
            }
            GameTestSupport.observeVisible(h, c, h.absolutePos(table));
            runner.tick();
            h.assertTrue(result.status != null, "the batch should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.status + ": " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.STONE_PICKAXE), 2, "stone pickaxes");
            h.assertValueEqual(c.inventory().countItem(Items.STONE_AXE), 2, "stone axes");
            h.assertTrue(first.getItem(0).isEmpty(), "the deepslate source was used: " + first.getItem(0));
            h.assertTrue(second.getItem(0).isEmpty(), "the cobblestone source was used: " + second.getItem(0));
        });
    }

    @GameTest(maxTicks = 800, structure = GameTestArenas.ARENA_16)
    public void fullBagReportsTheUncollectedBatchRemainder(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos stone = new BlockPos(4, 2, 2);
        h.setBlock(stone, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)),
                "the stone source should be visible even with a full bag");
        Result result = new Result();
        BatchObtainTask task = new BatchObtainTask(
                List.of(new CraftPlanner.Target("minecraft:cobblestone", 1)),
                false,
                true,
                new MinecraftObtainSubtaskFactory());
        ExecutionCoordinator runner = batch(companion, result, task);

        h.succeedWhen(() -> {
            if (h.getBlockState(stone).is(Blocks.STONE)) {
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone));
            }
            runner.tick();
            h.assertTrue(result.status != null, "the full-bag batch should stop");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    result.summary.contains("my inventory is full"),
                    "the refusal names the full bag: " + result.summary);
            TaskWorkResult work = task.workResult().orElseThrow();
            TaskWorkResult.Line remainder = work.lines().stream()
                    .filter(line -> line.itemId().equals("minecraft:cobblestone"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the requested item is missing from the result: " + work));
            h.assertValueEqual(remainder.requested(), 1, "one cobblestone was requested");
            h.assertValueEqual(remainder.verified(), 0, "the full bag acquired no cobblestone");
            h.assertValueEqual(remainder.remaining(), 1, "the uncollected remainder is explicit");
            h.assertTrue(
                    work.blocker().contains("my inventory is full"), "the result keeps the precise blocker: " + work);
        });
    }

    /** Room is measured on the real stacks: compatibility follows components, and only a fully consumed stack frees. */
    @GameTest(structure = GameTestArenas.ARENA_16)
    public void stackSpaceCountsOnlyCompatibleRoomAndRealFreedSlots(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Container bag = c.inventory();
        h.assertValueEqual(StackSpace.freeRoom(bag, "minecraft:cobblestone"), 0, "an empty bag has no room");
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
        h.assertValueEqual(StackSpace.freeRoom(bag, "minecraft:cobblestone"), 1, "a 63 stack takes one more");
        ItemStack renamed = new ItemStack(Items.COBBLESTONE, 63);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
        bag.setItem(1, renamed);
        h.assertValueEqual(
                StackSpace.freeRoom(bag, "minecraft:cobblestone"),
                1,
                "a stack with different components is no room for a plain one");
        bag.setItem(2, new ItemStack(Items.OAK_LOG));
        h.assertValueEqual(StackSpace.emptiedSlots(bag, "minecraft:oak_log", 1), 1, "the only log frees its slot");
        bag.setItem(3, new ItemStack(Items.BIRCH_LOG, 64));
        h.assertValueEqual(
                StackSpace.emptiedSlots(bag, "minecraft:birch_log", 1), 0, "one log out of 64 frees no slot");
        h.assertValueEqual(
                StackSpace.emptiedSlots(bag, "minecraft:birch_log", 64), 1, "the whole stack frees its slot");
        h.assertValueEqual(StackSpace.maxStack("minecraft:iron_pickaxe"), 1, "a tool has its own stack limit");
        h.assertValueEqual(StackSpace.maxStack("minecraft:cobblestone"), 64, "cobblestone stacks to 64");
        h.succeed();
    }

    /**
     * Every slot is full, one of them with 63 cobblestone: the last cobblestone of a 64 request fills that stack, so
     * the work starts without a chest and without moving anything the owner is carrying.
     */
    @GameTest(maxTicks = 800, structure = GameTestArenas.ARENA_16)
    public void aCompatiblePartialStackTakesTheLastItemWithoutMovingAnything(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos stone = new BlockPos(4, 2, 2);
        h.setBlock(stone, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.inventory().setItem(0, new ItemStack(Items.COBBLESTONE, 63));
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)),
                "the stone source should be visible with a full bag");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(companion, result, obtainTask("minecraft:cobblestone", 64, false, true));

        h.succeedWhen(() -> {
            if (h.getBlockState(stone).is(Blocks.STONE)) {
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone));
            }
            runner.tick();
            h.assertTrue(result.status != null, "the obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.status + ": " + result.summary);
            h.assertValueEqual(carried(companion, "minecraft:cobblestone"), 64, "the last cobblestone was collected");
            h.assertValueEqual(carried(companion, "minecraft:dirt"), 26 * 64, "nothing the owner carried was moved");
        });
    }

    /** A craft consumes its ingredient before its result arrives, so its own slot pays for the result. */
    @GameTest(maxTicks = 800, structure = GameTestArenas.ARENA_16)
    public void aCraftThatConsumesItsIngredientSlotRunsWithAFullBag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.inventory().setItem(0, new ItemStack(Items.OAK_LOG));
        Result result = new Result();
        ExecutionCoordinator runner = obtain(companion, result, obtainTask("minecraft:oak_planks", 4, false, true));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.status + ": " + result.summary);
            h.assertValueEqual(carried(companion, "minecraft:oak_planks"), 4, "four planks were crafted");
            h.assertValueEqual(carried(companion, "minecraft:oak_log"), 0, "the log paid for them");
            h.assertValueEqual(carried(companion, "minecraft:dirt"), 26 * 64, "nothing else was touched");
        });
    }

    /** One missing slot moves one stack, not the whole bag, and the request completes from the chest. */
    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_16)
    public void oneNeededSlotMovesOneStackNotTheWholeBag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos chestPos = new BlockPos(4, 2, 2);
        h.setBlock(chestPos, Blocks.CHEST);
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(chestPos));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        chest.setChanged();
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.inventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(chestPos)), "the chest should be visible");
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 1600, () -> false);
        runner.setEventSink(result);
        // Container contents are remembered evidence: the companion has to open the chest once before the planner
        // can use what is inside it.
        runner.start(new CheckContainerTask(h.absolutePos(chestPos)), "check_container");
        int[] phase = {0};

        h.succeedWhen(() -> {
            GameTestSupport.observeVisible(h, companion, h.absolutePos(chestPos));
            runner.tick();
            if (phase[0] == 0) {
                h.assertTrue(
                        result.status == TaskStatus.SUCCESS,
                        "the chest was not checked: " + result.status + " " + result.summary);
                phase[0] = 1;
                result.status = null;
                result.summary = null;
                runner.start(obtainTask("minecraft:cobblestone", 68, true, false), "obtain_item");
            } else {
                h.assertTrue(result.status != null, "the obtain should finish");
                h.assertTrue(result.status == TaskStatus.SUCCESS, result.status + ": " + result.summary);
                h.assertValueEqual(carried(companion, "minecraft:cobblestone"), 68, "the four chest items were taken");
                h.assertValueEqual(carried(companion, "minecraft:dirt"), 25 * 64, "exactly one dirt stack moved");
                h.assertValueEqual(
                        dev.friendai.task.inventory.InventoryOps.count(
                                chest,
                                dev.friendai.task.inventory.ItemMatcher.parse("minecraft:dirt")
                                        .orElseThrow()),
                        64,
                        "the chest received exactly the one stack the step needed");
            }
            h.assertTrue(phase[0] == 1 && result.status != null, "still working");
        });
    }

    /**
     * A locked chest is nearer than an open one and the bag is full: nothing is written into the locked chest, and the
     * room the step needs comes from the open one through an ordinary walk-and-open.
     */
    @GameTest(maxTicks = 2400, structure = GameTestArenas.ARENA_16)
    public void aLockedChestIsSkippedForAnAuthorizedOneWhenTheBagIsFull(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        // The arena floor is stone; make it dirt so the one block the companion may mine is the one this test seeds.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 1, z, Blocks.DIRT);
            }
        }
        BlockPos stone = new BlockPos(4, 2, 5);
        h.setBlock(stone, Blocks.STONE);
        BlockPos lockedPos = new BlockPos(3, 2, 2);
        h.setBlock(lockedPos, Blocks.CHEST);
        BlockPos openPos = new BlockPos(6, 2, 4);
        h.setBlock(openPos, Blocks.CHEST);
        var registry = h.getLevel().registryAccess();
        var lockedEntity = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(lockedPos));
        var tag = lockedEntity.saveWithFullMetadata(registry);
        var lockTag = new net.minecraft.nbt.CompoundTag();
        lockTag.putString("items", "minecraft:stick");
        tag.put(net.minecraft.world.LockCode.TAG_LOCK, lockTag);
        lockedEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, registry, tag));
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.inventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)),
                "the stone source should be visible with a full bag");
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(lockedPos)),
                "the locked chest should be visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(openPos)),
                "the open chest should be visible");
        Container locked = (Container) h.getLevel().getBlockEntity(h.absolutePos(lockedPos));
        Container open = (Container) h.getLevel().getBlockEntity(h.absolutePos(openPos));
        Result result = new Result();
        ExecutionCoordinator runner = obtain(companion, result, obtainTask("minecraft:cobblestone", 65, true, true));

        h.succeedWhen(() -> {
            if (h.getBlockState(stone).is(Blocks.STONE)) {
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone));
            }
            runner.tick();
            h.assertTrue(result.status != null, "the obtain should finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, result.status + ": " + result.summary);
            h.assertValueEqual(
                    carried(companion, "minecraft:cobblestone"),
                    65,
                    "the mined cobblestone was collected: " + result.summary);
            h.assertValueEqual(carried(companion, "minecraft:dirt"), 25 * 64, "exactly one dirt stack moved");
            h.assertValueEqual(
                    dev.friendai.task.inventory.InventoryOps.count(
                            open,
                            dev.friendai.task.inventory.ItemMatcher.parse("minecraft:dirt")
                                    .orElseThrow()),
                    64,
                    "the open chest takes the overflow stack");
            h.assertValueEqual(
                    dev.friendai.task.inventory.InventoryOps.count(
                            locked,
                            dev.friendai.task.inventory.ItemMatcher.parse("minecraft:dirt")
                                    .orElseThrow()),
                    0,
                    "the locked chest must stay untouched");
        });
    }

    /** A full stack of the requested item is still full: the request stops honestly instead of starting work. */
    @GameTest(maxTicks = 400, structure = GameTestArenas.ARENA_16)
    public void aFullSameItemStackCannotTakeOneMore(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos stone = new BlockPos(4, 2, 2);
        h.setBlock(stone, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.inventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)),
                "the stone source should be visible with a full bag");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(companion, result, obtainTask("minecraft:cobblestone", 65, false, true));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the obtain should stop");
            h.assertTrue(result.status == TaskStatus.FAILED, result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("my inventory is full"), "summary was " + result.summary);
            h.assertValueEqual(carried(companion, "minecraft:cobblestone"), 64, "the carried stack is intact");
            h.assertValueEqual(carried(companion, "minecraft:dirt"), 26 * 64, "nothing was lost or moved");
        });
    }

    /**
     * The impossible half of the stone-tool order: the wood is reachable, the stone is not, and exploring is off. The
     * batch gathers the wood first and then stops on the missing stone family instead of touring the same hole. The
     * gathered wood stays in the bag and the result names exactly one actionable blocker.
     */
    @GameTest(maxTicks = 1600, environment = "friendai-gametest:obtain_no_stone", structure = GameTestArenas.ARENA_16)
    public void impossibleStoneOrderKeepsItsWoodenWorkAndNamesOneBlocker(GameTestHelper h) {
        GameTestSupport.platformAt(h, 60, 16);
        // The deliberate observation can see the platform itself from its edge. Use a floor
        // outside the stone-tool ingredient tag so the fixture really has no usable stone.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) h.setBlock(x, 60, z, Blocks.SMOOTH_QUARTZ);
        }
        h.setBlock(2, 61, 4, Blocks.CRAFTING_TABLE);
        List<BlockPos> logs = List.of(new BlockPos(4, 61, 2), new BlockPos(4, 61, 7));
        logs.forEach(log -> h.setBlock(log, Blocks.OAK_LOG));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 61, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        for (BlockPos log : logs) {
            h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(log)), "the log at " + log + " is visible");
        }
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(2, 61, 4))),
                "the crafting table is visible");
        Result result = new Result();
        BatchObtainTask task = new BatchObtainTask(
                List.of(
                        new CraftPlanner.Target("minecraft:wooden_pickaxe", 1),
                        new CraftPlanner.Target("minecraft:stone_pickaxe", 1)),
                false,
                false,
                8,
                1600,
                16,
                64,
                600,
                "local",
                null,
                null,
                new MinecraftObtainSubtaskFactory());
        ExecutionCoordinator runner = batch(c, result, task);
        long startTick = h.getLevel().getGameTime();
        String[] debug = {""};
        boolean[] logged = {false};
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the impossible order did not finish: " + task.debugStatus());
            if (!logged[0]) {
                logged[0] = true;
                debug[0] = task.debugStatus();
                dev.friendai.FriendaiMod.LOGGER.info(
                        "[impossible-order] elapsedTicks={} status={} debug={} summary={} work={}",
                        h.getLevel().getGameTime() - startTick,
                        result.status,
                        debug[0],
                        result.summary,
                        task.workResult().map(TaskWorkResult::compact).orElse("<none>"));
            }
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    result.summary.contains("#minecraft:stone_tool_materials"),
                    "the blocker must name the missing material family: " + result.summary);
            h.assertTrue(
                    result.summary.contains("exploring is off"),
                    "the blocker must say why it could not be found: " + result.summary);
            int wood = c.inventory().countItem(Items.OAK_LOG)
                    + c.inventory().countItem(Items.OAK_PLANKS)
                    + c.inventory().countItem(Items.STICK);
            h.assertTrue(wood > 0, "the gathered wood stays in the bag: " + result.summary);
            int ticks = boundedCount(debug[0], "t=", "/1600");
            int children = boundedCount(debug[0], "children=", "/8");
            h.assertTrue(ticks < 1600, "the order stopped for its blocker, not for time: " + debug[0]);
            h.assertTrue(children < 8, "the order stopped for its blocker, not for the step cap: " + debug[0]);
            TaskWorkResult work = task.workResult().orElseThrow();
            h.assertTrue(!work.blocker().isBlank(), "the result carries one blocker: " + work.compact());
            h.assertTrue(
                    result.summary.startsWith(work.blocker()),
                    "the blocker is the failure text, not a second one: " + work.compact());
            int carriedWooden = c.inventory().countItem(Items.WOODEN_PICKAXE)
                    + (c.getMainHandItem().is(Items.WOODEN_PICKAXE)
                            ? c.getMainHandItem().getCount()
                            : 0);
            h.assertValueEqual(
                    work.reasonCode(), carriedWooden > 0 ? "partial" : "blocked", "reason matches the remaining work");
            h.assertValueEqual(work.lines().size(), 2, "one result for each requested tool");
            h.assertValueEqual(
                    work.lines().get(0).verified(), carriedWooden, "the crafted wooden pickaxe stays accounted for");
            h.assertValueEqual(work.lines().get(1).verified(), 0, "the missing stone tool cannot be reported as made");
            h.assertTrue(h.getLevel().getGameTime() - startTick <= 1600, "the order stayed inside its tick bound");
        });
    }

    /** Reads the used part of {@code prefix<number><suffix>} from a task's debug status. */
    private static int boundedCount(String debug, String prefix, String suffix) {
        int start = debug.indexOf(prefix);
        int end = start < 0 ? -1 : debug.indexOf(suffix, start + prefix.length());
        if (start < 0 || end < 0) throw new AssertionError("no " + prefix + " count in: " + debug);
        return Integer.parseInt(debug.substring(start + prefix.length(), end));
    }

    @GameTest(maxTicks = 600)
    public void batchNamesWhatItCouldNotGet(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = batch(
                c,
                result,
                new BatchObtainTask(
                        List.of(new CraftPlanner.Target("minecraft:dragon_egg", 1)),
                        false,
                        false,
                        new MinecraftObtainSubtaskFactory()));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("couldn't get 1 minecraft:dragon_egg for minecraft:dragon_egg:"),
                    result.summary);
        });
    }

    /** A trusted player's unauthorized expedition is refused by the preview with the reason execution would give. */
    @GameTest(structure = GameTestArenas.ARENA_16, environment = "friendai-gametest:search_trusted_expedition")
    public void planObtainRefusesATrustedExpedition(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var config = state.config();
        config.tasks.resources.allowExpeditions = true;
        config.companion.chunkLoading.enabled = true;
        state.applyConfig(config);
        h.setBlock(4, 2, 2, Blocks.IRON_ORE);
        c.inventory().addItem(new ItemStack(Items.STONE_PICKAXE));
        ToolExecutor tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        String json = "{\"item\":\"minecraft:raw_iron\",\"count\":1,\"scope\":\"expedition\"}";
        ToolResult trusted = tools.execute(
                new ToolCall("1", "plan_obtain", JsonParser.parseString(json).getAsJsonObject(), json, null),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED));
        h.assertTrue(trusted.error(), "the preview must refuse what the queued call would refuse");
        h.assertTrue(trusted.content().contains("expeditions are disabled or owner-only"), trusted.content());

        ToolResult granted = tools.execute(
                new ToolCall("2", "plan_obtain", JsonParser.parseString(json).getAsJsonObject(), json, null),
                new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(granted.error(), granted.content());
        h.assertTrue(
                granted.content().contains("search scope: expedition (512 blocks, 12000 ticks"),
                "the preview names the scope execution will run: " + granted.content());
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
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

    /**
     * A wooden pickaxe needs planks, and the plan reads the logs out of the tree: the companion must fell the whole
     * tree, not stop after the three logs the recipe happens to need.
     */
    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
    public void obtainWoodenPickaxeFellsAWholeTree(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        List<BlockPos> logs = new java.util.ArrayList<>();
        for (int y = 2; y <= 6; y++) {
            BlockPos p = new BlockPos(8, y, 8);
            h.setBlock(p, Blocks.SPRUCE_LOG);
            logs.add(p);
        }
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                BlockPos p = new BlockPos(x, 7, z);
                if (h.getBlockState(p).isAir()) {
                    h.setBlock(p, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
            }
        }
        h.setBlock(3, 2, 3, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 2, 8))), "trunk base visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(3, 2, 3))), "table visible");
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:wooden_pickaxe", 1, true, true));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            for (BlockPos p : logs) {
                h.assertTrue(h.getBlockState(p).isAir(), "log left at " + p + "; " + result.summary);
            }
            h.assertValueEqual(c.inventory().countItem(Items.WOODEN_PICKAXE), 1, "pickaxes");
        });
    }

    /**
     * Two cakes need six buckets of milk but only three buckets: each cake hands its three buckets back before the
     * companion fills them again, so the order completes without a fourth bucket.
     */
    @GameTest(maxTicks = 9600, structure = GameTestArenas.ARENA_16)
    public void obtainTwoCakesReusesTheCarriedBuckets(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(12, 2, 12, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 4, 2, 4);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 5, 2, 5);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 6, 2, 6);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 7, 2, 7);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 8, 2, 8);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 9, 2, 9);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(12, 2, 12))),
                "crafting table should be visible");
        c.inventory().addItem(new ItemStack(Items.BUCKET, 3));
        c.inventory().addItem(new ItemStack(Items.SUGAR, 4));
        c.inventory().addItem(new ItemStack(Items.EGG, 2));
        c.inventory().addItem(new ItemStack(Items.WHEAT, 6));
        Result result = new Result();
        ExecutionCoordinator runner = obtain(c, result, obtainTask("minecraft:cake", 2, false, false));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.CAKE), 2, "cakes");
            h.assertValueEqual(c.inventory().countItem(Items.BUCKET), 3, "the same three buckets came back");
            h.assertValueEqual(c.inventory().countItem(Items.MILK_BUCKET), 0, "no milk left over");
        });
    }
}
