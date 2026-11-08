package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.features.construction.minecraft.task.ClearAreaTask;
import dev.friendai.features.inventory.minecraft.task.PickupItemsTask;
import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.minecraft.task.ExcavateTask;
import dev.friendai.features.mining.minecraft.task.FellTreeTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEventData;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.inventory.DropSettle;
import dev.friendai.world.movement.LevelCellView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        return h.getLevel()
                .getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(8.0));
    }

    private static boolean stoneAt(GameTestHelper h, int x, int y, int z) {
        return h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, y, z))).is(Blocks.STONE);
    }

    private static final class Diagnostics implements TaskEventSink {
        final List<TaskLogEvent> events = new ArrayList<>();

        @Override
        public void onFinished(int id, String tool, TaskStatus status, String summary) {}

        @Override
        public void onCancelled(int id, String tool, String reason) {}

        @Override
        public void onDiagnostic(TaskLogEvent event) {
            events.add(event);
        }

        List<TaskLogEvent> reason(String reason) {
            return events.stream()
                    .filter(e -> reason.equals(e.facts().get("reason")))
                    .toList();
        }
    }

    private static ExecutionCoordinator recordingRunner(CompanionEntity c, Diagnostics events) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setEventSink(events);
        runner.start(new StayTask(), "pickup_items");
        return runner;
    }

    @GameTest
    public void waitingDiagnosticsUseCachedDropFacts(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Diagnostics events = new Diagnostics();
        ExecutionCoordinator runner = recordingRunner(c, events);
        TaskContext context = new TaskContext(c, h.getLevel(), runner);
        ItemEntity delayed = drop(h, 6.5, 2, 2.5, new ItemStack(Items.COBBLESTONE));
        delayed.setOnGround(true);
        delayed.setDefaultPickUpDelay();
        ItemEntity falling = drop(h, 7.5, 5, 2.5, new ItemStack(Items.DIRT));
        falling.setOnGround(false);
        falling.setNoPickUpDelay();
        ItemEntity both = drop(h, 7.5, 6, 2.5, new ItemStack(Items.GRAVEL));
        both.setOnGround(false);
        both.setDefaultPickUpDelay();
        DropCollector collector = new DropCollector(c.position(), 8);
        for (int i = 0; i < 8; i++) collector.tick(context);
        h.assertValueEqual(events.reason("waiting_drop").size(), 1, "one decision per waiting episode");
        TaskLogEvent event = events.reason("waiting_drop").getFirst();
        h.assertValueEqual(event.event(), "inventory.delta", "existing decision event");
        Map<String, String> facts = event.facts();
        h.assertValueEqual(facts.get("drops"), "3", "eligible drops");
        h.assertValueEqual(facts.get("aimed"), "0", "neither drop is ready");
        h.assertValueEqual(facts.get("delayed"), "3", "falling and delayed counted once");
        h.assertValueEqual(facts.get("collected"), "0", "preserved receipt total");
        h.assertValueEqual(facts.get("elapsed"), "1", "waiting episode starts on first tick");
        String first = delayed.blockPosition().toShortString();
        h.assertValueEqual(facts.get("first"), first, "nearest eligible cell");
        String cached = collector.debugStatus();
        h.assertTrue(cached.contains("drops=3 aimed=0 delayed=3 first=" + first + " elapsed=8"), cached);
        delayed.discard();
        falling.discard();
        both.discard();
        h.assertValueEqual(collector.debugStatus(), cached, "status must not rescan removed entities");
        collector.stop(context);
        runner.stopWithoutCleanup("test complete");
        h.succeed();
    }

    @GameTest
    public void roundDiagnosticsKeepBoundedLeftoversAndTotals(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Diagnostics events = new Diagnostics();
        ExecutionCoordinator runner = recordingRunner(c, events);
        TaskContext context = new TaskContext(c, h.getLevel(), runner);
        for (var item : List.of(
                Items.STONE,
                Items.DIRT,
                Items.GRANITE,
                Items.DIORITE,
                Items.ANDESITE,
                Items.SAND,
                Items.GRAVEL,
                Items.COBBLESTONE)) {
            drop(h, 2.5, 2, 2.5, new ItemStack(item));
        }
        List<ItemEntity> left = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ItemEntity item = drop(h, 6.5, 5 + i, 2.5, new ItemStack(Items.OAK_LOG));
            item.setOnGround(true);
            left.add(item);
        }
        DropCollector collector = new DropCollector(c.position(), 12);
        DropCollector.State state = DropCollector.State.COLLECTING;
        for (int i = 0; i < 121 && state != DropCollector.State.DONE; i++) state = collector.tick(context);
        h.assertValueEqual(state, DropCollector.State.DONE, "round retains its tick budget");
        TaskLogEvent event = events.reason("pickup_round").getFirst();
        h.assertValueEqual(event.facts().get("left"), "5", "all leftovers counted");
        String packed = event.facts().get("leftovers");
        h.assertValueEqual(packed.split("; ").length, 4, "only four leftover cells published");
        h.assertFalse(packed.contains(left.get(4).blockPosition().toShortString()), "fifth cell omitted");
        LogContext full = new LogContext("trace", 1, 2, 3, 4, 5, 6, 7, "", 0);
        Map<String, String> data = LogEventData.build(full, event.facts());
        h.assertTrue(data.size() <= 16, "existing data cap");
        h.assertValueEqual(data.get("reason"), "pickup_round", "reason survives full context");
        h.assertValueEqual(data.get("collected"), "8", "total survives full context");
        h.assertValueEqual(data.get("left"), "5", "left count survives full context");
        h.assertValueEqual(data.get("leftovers"), packed, "leftover cells survive full context");
        h.assertTrue(data.containsKey("truncated"), "omitted receipt summary is explicit");
        for (ItemEntity item : left) item.discard();
        DropCollector empty = new DropCollector(c.position(), 12);
        h.assertValueEqual(empty.tick(context), DropCollector.State.DONE, "empty round completes");
        TaskLogEvent emptyEvent = events.reason("pickup_round").getLast();
        h.assertValueEqual(emptyEvent.facts().get("left"), "0", "empty round has no leftovers");
        h.assertTrue(empty.debugStatus().contains("first=none"), "empty round invents no nearest cell");
        runner.stopWithoutCleanup("test complete");
        h.succeed();
    }

    @GameTest
    public void collectingTaskStatusesRetainTheirStage(GameTestHelper h) throws ReflectiveOperationException {
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        var geometry = ExcavationGeometry.box(at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ())
                .orElseThrow();
        var spec = ExcavationSpec.of(geometry, 1, null, 0, true, false, false, false, 0)
                .orElseThrow();
        Task[] tasks = {
            new PickupItemsTask(8),
            new ClearAreaTask(at, 1, 1, 1, false, true),
            new ExcavateTask(spec),
            new FellTreeTask(
                    new TreeFinder.TreeInfo("oak_log", List.of(at), at, 1, TreeFinder.Kind.STANDING_TREE, 0, "north"),
                    null,
                    1,
                    true)
        };
        for (Task task : tasks) {
            String prefix = task.debugStatus();
            // Focus this fixture on status composition, without executing the unrelated mining/building stages.
            var field = task.getClass().getDeclaredField(task instanceof PickupItemsTask ? "collector" : "sweep");
            field.setAccessible(true);
            field.set(task, new DropCollector(Vec3.atCenterOf(at), 8));
            String status = task.debugStatus();
            h.assertTrue(status.startsWith(prefix), "stage retained: " + status);
            h.assertTrue(status.contains("drops=0 aimed=0 delayed=0 first=none elapsed=0"), status);
        }
        h.succeed();
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
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 3, "cobblestone not collected");
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
            DropCollector.State state = collector.tick(ctx);
            if (ticks[0] < 10) {
                h.assertTrue(
                        state == DropCollector.State.COLLECTING,
                        "a round must wait for a drop inside its pickup delay, not end");
                h.assertValueEqual(
                        companion.inventory().countItem(Items.COBBLESTONE),
                        0,
                        "collected before the pickup delay ran out");
                return;
            }
            if (ticks[0] >= 15) {
                h.assertValueEqual(
                        companion.inventory().countItem(Items.COBBLESTONE), 3, "not collected after the pickup delay");
            }
        });
    }

    @GameTest(maxTicks = 220)
    public void collectorTakesADropAboveMagmaWithoutSteppingOnIt(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(5, 2, 5, Blocks.MAGMA_BLOCK);
        ItemEntity item = drop(h, 5.5, 3.0, 5.5, new ItemStack(Items.COBBLESTONE, 3));
        DropCollector collector = new DropCollector(companion.position(), 8);
        TaskContext ctx = ctx(h, companion);
        h.succeedWhen(() -> {
            boolean done = collector.tick(ctx) == DropCollector.State.DONE;
            h.assertTrue(
                    !companion.blockPosition().equals(h.absolutePos(new BlockPos(5, 2, 5))),
                    "the companion stood in the magma to reach the drop");
            if (!done) {
                return;
            }
            // Magma hurts a companion standing on it but leaves an item alone: the drop is taken from a safe spot.
            h.assertValueEqual(
                    companion.inventory().countItem(Items.COBBLESTONE), 3, "the drop above the magma was not taken");
            h.assertTrue(item.isRemoved(), "the taken drop should be gone");
        });
    }

    @GameTest
    public void itemHazardFilterMatchesVanilla(GameTestHelper h) {
        h.assertTrue(LevelCellView.destroysItems(Blocks.FIRE.defaultBlockState()), "fire destroys items");
        h.assertTrue(LevelCellView.destroysItems(Blocks.CACTUS.defaultBlockState()), "a cactus destroys items");
        h.assertTrue(LevelCellView.destroysItems(Blocks.LAVA.defaultBlockState()), "lava destroys items");
        h.assertFalse(
                LevelCellView.destroysItems(Blocks.MAGMA_BLOCK.defaultBlockState()),
                "magma only hurts a mob, an item is safe on it");
        h.assertFalse(
                LevelCellView.destroysItems(Blocks.SWEET_BERRY_BUSH.defaultBlockState()),
                "a berry bush only hurts a mob, a berry resting in it is safe");
        h.succeed();
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
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0, "collected a sealed drop");
                return;
            }
            h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0, "collected a sealed drop");
            h.assertValueEqual(drops(h, companion).size(), 1, "the sealed drop should still be on the ground");
            h.assertTrue(
                    stoneAt(h, 4, 3, 5)
                            && stoneAt(h, 6, 3, 5)
                            && stoneAt(h, 5, 3, 4)
                            && stoneAt(h, 5, 3, 6)
                            && stoneAt(h, 5, 4, 5),
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
                h.assertValueEqual(
                        companion.inventory().countItem(Items.COBBLESTONE), 0, "collected while a hostile was near");
                return;
            }
            h.assertValueEqual(
                    companion.inventory().countItem(Items.COBBLESTONE), 0, "collected while a hostile was near");
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
            h.assertValueEqual(
                    companion.inventory().countItem(Items.COBBLESTONE), 3, "the settled drop is not in the inventory");
        });
    }

    @GameTest
    public void repeatedNearbyPickupDoesNotDuplicateAnItem(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemEntity item = drop(h, 2.5, 2.0, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        h.assertValueEqual(companion.collectNearbyItems(2.0), 3, "first pickup count");
        h.assertValueEqual(companion.collectNearbyItems(2.0), 0, "second pickup moved an already collected stack");
        h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 3, "repeated pickup duplicated items");
        h.assertTrue(item.isRemoved(), "fully collected entity should be removed");
        h.succeed();
    }
}
