package dev.friendai.test;

import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.pathing.Pos;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceService;
import dev.friendai.place.PlaceSource;
import dev.friendai.place.PlaceValues;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.DepositTask;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class FreeTimeTests {
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

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner r = new TaskRunner(c, () -> 300, () -> false);
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
        PlaceService.book(c).remember(new PlaceValues(name, PlaceKind.STORAGE, "",
                        c.level().dimension().identifier().toString(),
                        new Pos(anchor.getX(), anchor.getY(), anchor.getZ()), r, Set.of(), PlaceSource.MANUAL,
                        c.ownerUuid(), ""),
                c.level().getGameTime());
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
        TaskRunner runner = runner(c, result);
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
        @Override public String type() { return "quick"; }
        @Override public void start(dev.friendai.task.TaskContext c) {}
        @Override public TaskStatus tick(dev.friendai.task.TaskContext c) { return TaskStatus.SUCCESS; }
        @Override public void stop(dev.friendai.task.TaskContext c) {}
        @Override public String describe() { return "quick"; }
        @Override public String resultSummary() { return "done"; }
    }

    @GameTest(maxTicks = 200)
    public void marker(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = companion(h);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        var stay = new dev.friendai.queue.QueuedStep("stay", new com.google.gson.JsonObject(), null,
                java.util.List.of(), dev.friendai.queue.GuardAction.WAIT, dev.friendai.queue.FailurePolicy.ASK, 1,
                java.util.List.of());
        long plan = runner.enqueuePlan("free time", java.util.List.of(stay),
                java.util.List.of(ctx -> new dev.friendai.task.builtin.StayTask()),
                new dev.friendai.queue.PlanOptions(-10, dev.friendai.queue.FailurePolicy.ASK, 0));
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
        var builtins = dev.friendai.tool.builtin.FriendaiTools.build(() -> 16,
                dev.friendai.tool.builtin.MemoryAccess.DISABLED);
        var args = com.google.gson.JsonParser.parseString(
                "{\"goal\":\"g\",\"priority\":-10,\"steps\":[{\"tool\":\"stay\"},{\"tool\":\"attack_player\","
                        + "\"args\":\"{\\\"player\\\":\\\"x\\\"}\"}]}").getAsJsonObject();
        var caller = new dev.friendai.tool.Caller(c.ownerUuid(), "owner", dev.friendai.tool.AccessLevel.OWNER);
        var refused = dev.friendai.tool.builtin.FriendaiTools.addPlan(c, caller, args, builtins.plannable(), false,
                Set.of("attack_player"));
        h.assertTrue(refused.error(), "a denied step must be refused: " + refused.content());
        h.assertTrue(refused.content().contains("attack_player is not allowed in free time"), refused.content());
        h.assertValueEqual(c.taskRunner().queue().planCount(), 0, "nothing queued");
        var ok = dev.friendai.tool.builtin.FriendaiTools.addPlan(c, caller, com.google.gson.JsonParser.parseString(
                "{\"goal\":\"g\",\"priority\":-10,\"steps\":[{\"tool\":\"stay\"}]}").getAsJsonObject(),
                builtins.taskTools(), false, Set.of("attack_player"));
        h.assertTrue(!ok.error(), "an allowed plan is queued: " + ok.content());
        h.assertValueEqual(c.taskRunner().queue().plans().getFirst().priority(), -10, "free-time priority");
        h.succeed();
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
        TaskRunner runner = runner(c, result);
        runner.start(new DepositTask("full chests", AutonomySettings.DEFAULT_KEEP), "deposit_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "storage is full", "summary");
            h.assertValueEqual(bag(c, "minecraft:raw_gold"), 10, "nothing moved");
        });
    }
}
