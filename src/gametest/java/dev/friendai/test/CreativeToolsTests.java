package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.core.CreativeModeService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.features.creative.minecraft.CreativeItemCapability;
import dev.friendai.features.creative.minecraft.task.CreateItemsTask;
import dev.friendai.task.TaskContext;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class CreativeToolsTests {
    private static void makeOperator(ServerPlayer player) {
        player.level()
                .getServer()
                .getPlayerList()
                .op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
    }

    private static CompanionEntity creativeCompanion(GameTestHelper h, ServerPlayer owner) {
        makeOperator(owner);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(
                CreativeModeService.requestMode(GameTestSupport.runtime(h), companion, owner, true)
                        == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");
        return companion;
    }

    private static Caller ownerCaller(ServerPlayer owner) {
        return new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER);
    }

    private static CreateItemsTask start(
            GameTestHelper h,
            CompanionEntity companion,
            ServerPlayer owner,
            Item item,
            long count,
            CreateItemsTask.Destination destination) {
        CreateItemsTask task = new CreateItemsTask(new ItemStack(item), count, destination);
        companion.executionCoordinator().start(task, "create_item", ownerCaller(owner), true);
        return task;
    }

    private static void tick(CompanionEntity companion, int count) {
        for (int i = 0; i < count; i++) {
            companion.executionCoordinator().tick();
        }
    }

    @GameTest
    public void sharedCreativeCapabilityDeliversOneBoundedBatchAndRechecksPermission(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        TaskContext context = new TaskContext(
                companion,
                h.getLevel(),
                companion.executionCoordinator(),
                new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()));

        CreativeItemCapability.Batch first = CreativeItemCapability.deliverOneBatch(
                context, new ItemStack(Items.DIAMOND), 130, CreativeItemCapability.Destination.COMPANION);
        h.assertTrue(first.succeeded() && first.inserted() == 64, "capability did not deliver one stack");
        h.assertTrue(
                companion.inventory().countItem(Items.DIAMOND) == 64,
                "capability created " + companion.inventory().countItem(Items.DIAMOND) + " items in one batch");

        owner.level().getServer().getPlayerList().deop(owner.nameAndId());
        CreativeItemCapability.Batch revoked = CreativeItemCapability.deliverOneBatch(
                context, new ItemStack(Items.DIAMOND), 66, CreativeItemCapability.Destination.COMPANION);
        h.assertTrue(
                revoked.inserted() == 0 && revoked.failure() == CreativeItemCapability.Failure.UNAUTHORIZED,
                "revoked permission allowed another capability batch");
        h.assertTrue(
                companion.inventory().countItem(Items.DIAMOND) == 64,
                "permission revocation changed the delivered item count");
        h.succeed();
    }

    @GameTest
    public void createItemSplitsStacksAndCreatesNonStackableItems(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        CreateItemsTask stackable = start(h, companion, owner, Items.DIAMOND, 130, CreateItemsTask.Destination.SELF);
        tick(companion, 3);
        h.assertTrue(
                companion.inventory().countItem(Items.DIAMOND) == 130,
                "stacked delivery created " + companion.inventory().countItem(Items.DIAMOND));
        h.assertTrue(
                stackable.delivered() == 130 && stackable.remaining() == 0,
                "stacked task did not report exact completion");

        CreateItemsTask nonStackable =
                start(h, companion, owner, Items.DIAMOND_SWORD, 2, CreateItemsTask.Destination.SELF);
        tick(companion, 2);
        h.assertTrue(
                companion.inventory().countItem(Items.DIAMOND_SWORD) == 2,
                "non-stackable delivery created " + companion.inventory().countItem(Items.DIAMOND_SWORD));
        h.assertTrue(nonStackable.remaining() == 0, "non-stackable task did not finish");
        h.succeed();
    }

    @GameTest
    public void createItemReportsFullAndPartialInventoryCapacity(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            companion.inventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        CreateItemsTask full = start(h, companion, owner, Items.DIAMOND, 10, CreateItemsTask.Destination.SELF);
        tick(companion, 1);
        h.assertTrue(
                full.delivered() == 0 && full.remaining() == 10,
                "full inventory delivered " + full.delivered() + " and left " + full.remaining());
        h.assertTrue(
                full.resultSummary().contains("10 remaining"), "full-inventory result was " + full.resultSummary());

        companion.executionCoordinator().stop("reset for partial inventory test");
        companion.inventory().setItem(0, ItemStack.EMPTY);
        CreateItemsTask partial = start(h, companion, owner, Items.DIAMOND, 100, CreateItemsTask.Destination.SELF);
        tick(companion, 2);
        h.assertTrue(
                partial.delivered() == 64 && partial.remaining() == 36,
                "partial delivery was " + partial.delivered() + "/" + partial.remaining());
        h.assertTrue(
                partial.resultSummary().contains("36 remaining"),
                "partial-inventory result was " + partial.resultSummary());
        h.succeed();
    }

    @GameTest
    public void createItemCancellationAndPermissionRevocationStopFutureBatches(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        CreateItemsTask cancelled = start(h, companion, owner, Items.DIAMOND, 1_000, CreateItemsTask.Destination.SELF);
        tick(companion, 1);
        h.assertTrue(companion.inventory().countItem(Items.DIAMOND) == 64, "first bounded batch was not delivered");
        companion.executionCoordinator().stop("cancel test");
        tick(companion, 4);
        h.assertTrue(companion.inventory().countItem(Items.DIAMOND) == 64, "cancelled task continued creating items");
        h.assertTrue(cancelled.remaining() == 936, "cancellation changed the task's remaining count");

        CreateItemsTask revoked = start(h, companion, owner, Items.GOLD_INGOT, 1_000, CreateItemsTask.Destination.SELF);
        tick(companion, 1);
        owner.level().getServer().getPlayerList().deop(owner.nameAndId());
        tick(companion, 1);
        h.assertTrue(
                companion.inventory().countItem(Items.GOLD_INGOT) == 64,
                "permission revocation did not stop the next batch");
        h.assertTrue(revoked.remaining() == 936, "revoked task advanced after authorization was lost");
        h.succeed();
    }

    @GameTest
    public void createItemCanDeliverToTheNearbyOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        CreateItemsTask task = start(h, companion, owner, Items.GOLD_INGOT, 7, CreateItemsTask.Destination.OWNER);
        tick(companion, 1);
        h.assertTrue(
                owner.getInventory().countItem(Items.GOLD_INGOT) == 7,
                "nearby owner received " + owner.getInventory().countItem(Items.GOLD_INGOT));
        h.assertTrue(
                companion.inventory().countItem(Items.GOLD_INGOT) == 0,
                "owner delivery also filled the companion inventory");
        h.assertTrue(task.remaining() == 0, "owner delivery did not complete");
        h.succeed();
    }

    @GameTest
    public void createItemToolRejectsUnknownItemsAndFractionalCounts(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        ToolExecutor tools = GameTestToolRegistry.create(companion, () -> 32).bind(companion);
        Caller caller = ownerCaller(owner);

        JsonObject unknown = new JsonObject();
        unknown.addProperty("item", "friendai:not_registered");
        unknown.addProperty("count", 1);
        ToolResult unknownResult =
                tools.execute(new ToolCall("1", "create_item", unknown, unknown.toString(), null), caller);
        h.assertTrue(unknownResult.error(), "unregistered item was accepted");

        JsonObject fractional = new JsonObject();
        fractional.addProperty("item", "minecraft:diamond");
        fractional.addProperty("count", 1.5);
        ToolResult fractionalResult =
                tools.execute(new ToolCall("2", "create_item", fractional, fractional.toString(), null), caller);
        h.assertTrue(fractionalResult.error(), "fractional count was accepted");
        h.assertTrue(
                tools.specs(AccessLevel.OWNER).stream()
                        .anyMatch(spec -> spec.name().equals("create_item")),
                "create_item was not registered");
        h.succeed();
    }
}
