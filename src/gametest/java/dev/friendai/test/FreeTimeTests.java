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
