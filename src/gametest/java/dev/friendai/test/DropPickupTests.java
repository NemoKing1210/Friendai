package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskContext;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.inventory.DropSettle;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The drop collector: it walks to safe drops, waits for the vanilla pickup delay and leaves the rest alone. */
public class DropPickupTests {

    private static ItemEntity drop(GameTestHelper h, double x, double y, double z, ItemStack stack) {
        Vec3 p = h.absoluteVec(new Vec3(x, y, z));
        ItemEntity e = new ItemEntity(h.getLevel(), p.x, p.y, p.z, stack);
        e.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(e);
        return e;
    }

    private static TaskContext ctx(GameTestHelper h, CompanionEntity c) {
        return new TaskContext(c, h.getLevel());
    }

    private static List<ItemEntity> drops(GameTestHelper h, CompanionEntity c) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(8.0));
    }

    private static boolean stoneAt(GameTestHelper h, int x, int y, int z) {
        return h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, y, z))).is(Blocks.STONE);
    }

    @GameTest(maxTicks = 200)
    public void collectorWalksToADrop(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        drop(h, 6.5, 2.0, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        DropCollector collector = new DropCollector(companion.position(), 8);
        TaskContext ctx = ctx(h, companion);
        h.succeedWhen(() -> {
            collector.tick(ctx);
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 3,
                    "cobblestone not collected");
        });
    }

    @GameTest(maxTicks = 200)
    public void collectorWaitsForPickupDelay(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemEntity item = drop(h, 3.5, 2.0, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        item.setDefaultPickUpDelay();
        DropCollector collector = new DropCollector(companion.position(), 8);
        TaskContext ctx = ctx(h, companion);
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            collector.tick(ctx);
            if (ticks[0] < 10) {
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                        "collected before the pickup delay ran out");
                return;
            }
            if (ticks[0] >= 15) {
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 3,
                        "not collected after the pickup delay");
            }
        });
    }

    @GameTest(maxTicks = 220)
    public void collectorSkipsHazardousDrop(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(5, 2, 5, Blocks.MAGMA_BLOCK);
        drop(h, 5.5, 3.0, 5.5, new ItemStack(Items.COBBLESTONE, 3));
        DropCollector collector = new DropCollector(companion.position(), 8);
        TaskContext ctx = ctx(h, companion);
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            collector.tick(ctx);
            if (ticks[0] < 130) {
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                        "collected a drop over magma");
                return;
            }
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                    "collected a drop over magma");
            h.assertValueEqual(drops(h, companion).size(), 1, "the hazardous drop should still be on the ground");
        });
    }

    @GameTest(maxTicks = 220)
    public void collectorSkipsUnreachableDrop(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                for (int y = 2; y <= 4; y++) {
                    if (x == 5 && y == 3 && z == 5) {
                        h.setBlock(x, y, z, Blocks.AIR);
                    } else {
                        h.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        drop(h, 5.5, 3.0, 5.5, new ItemStack(Items.COBBLESTONE, 3));
        DropCollector collector = new DropCollector(companion.position(), 8);
        TaskContext ctx = ctx(h, companion);
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            collector.tick(ctx);
            if (ticks[0] < 130) {
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                        "collected a sealed drop");
                return;
            }
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                    "collected a sealed drop");
            h.assertValueEqual(drops(h, companion).size(), 1, "the sealed drop should still be on the ground");
            h.assertTrue(stoneAt(h, 4, 3, 5) && stoneAt(h, 6, 3, 5)
                    && stoneAt(h, 5, 3, 4) && stoneAt(h, 5, 3, 6) && stoneAt(h, 5, 4, 5),
                    "the collector broke the chamber open");
        });
    }

    @GameTest(maxTicks = 200)
    public void collectorDefersWhileAHostileIsNear(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(5, 2, 6, Blocks.STONE);
        h.setBlock(7, 2, 6, Blocks.STONE);
        h.setBlock(6, 2, 5, Blocks.STONE);
        h.setBlock(6, 2, 7, Blocks.STONE);
        h.setBlock(6, 3, 6, Blocks.STONE);
        h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 2, 6);
        drop(h, 4.5, 2.0, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        DropCollector collector = new DropCollector(companion.position(), 8);
        TaskContext ctx = ctx(h, companion);
        int[] ticks = {0};
        h.succeedWhen(() -> {
            if (++ticks[0] < 21) {
                collector.tick(ctx);
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                        "collected while a hostile was near");
                return;
            }
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0,
                    "collected while a hostile was near");
        });
    }

    @GameTest(maxTicks = 100)
    public void settleCollectsAtFeet(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemEntity item = drop(h, 2.5, 2.0, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        item.setDefaultPickUpDelay();
        DropSettle settle = new DropSettle(12, 2.0, null);
        TaskContext ctx = ctx(h, companion);
        int[] ticks = {0};
        h.succeedWhen(() -> {
            if (++ticks[0] <= 12) {
                settle.tick(ctx);
                return;
            }
            h.assertValueEqual(settle.collected(), 3, "the settle did not collect the drop at its feet");
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 3,
                    "the settled drop is not in the inventory");
        });
    }
}
