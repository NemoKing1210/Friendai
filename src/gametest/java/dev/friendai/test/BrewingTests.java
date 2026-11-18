package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.brewing.minecraft.task.BrewCheckpoints;
import dev.friendai.features.brewing.minecraft.task.BrewTask;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/** Live station and inventory evidence for brewing and checkpoint recovery. */
public class BrewingTests {
    private static final BlockPos STAND = new BlockPos(4, 2, 3);

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 1600)
    public void brewsStrengthAtAFoundStand(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        supplies(companion, 2);
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask task = new BrewTask("minecraft:strength", 2, null);
        task.start(context);

        h.succeedWhen(() -> {
            h.assertValueEqual(task.tick(context), TaskStatus.SUCCESS, task.resultSummary());
            assertResult(h, companion, task, 2, 0);
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 1600)
    public void restoresWhenFinalBottlesAreReadyAndReportsEachLocation(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        supplies(companion, 2);
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask original = new BrewTask("minecraft:strength", 2, h.absolutePos(STAND));
        original.start(context);
        BrewTask[] active = {original};
        h.succeedWhen(() -> {
            if (active[0] == original && potions(stand(h), Potions.STRENGTH) == 2) {
                TaskState checkpoint = new TaskState("brew_potion", original.saveData());
                var otherLevel = h.getLevel().getServer().getLevel(Level.NETHER);
                h.assertTrue(
                        BrewCheckpoints.brew(checkpoint, new TaskContext(companion, otherLevel))
                                .isEmpty(),
                        "an owned brewing batch cannot restore in another dimension");
                // A nearer, unrelated stand must not become the restored target.
                h.setBlock(2, 2, 3, Blocks.BREWING_STAND);
                for (int i = 0; i < companion.inventory().getContainerSize() - 1; i++) {
                    companion.inventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                }
                active[0] = (BrewTask) BrewCheckpoints.brew(checkpoint, context).orElseThrow();
                active[0].start(context);
            }
            h.assertValueEqual(active[0].tick(context), TaskStatus.SUCCESS, active[0].resultSummary());
            h.assertTrue(active[0] != original, "the final result must come from the restored task");
            assertResult(h, companion, active[0], 1, 1);
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void matchingPotionWithoutAConsumedFuelUseIsNotCollected(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        supplies(companion, 1);
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(STAND));
        task.start(context);
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertValueEqual(task.tick(context), TaskStatus.RUNNING, task.resultSummary());
                    h.assertValueEqual(potions(stand(h), Potions.WATER), 1, "wait until this batch stages");
                })
                .thenExecute(() -> {
                    stand(h).setItem(0, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
                    stand(h).setItem(3, ItemStack.EMPTY);
                    h.assertValueEqual(
                            task.tick(context), TaskStatus.FAILED, "a replacement bottle is not task output");
                    task.onTerminal(context);
                    h.assertValueEqual(potions(stand(h), Potions.AWKWARD), 1, "foreign potion stays in the stand");
                })
                .thenSucceed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void refusesAnOccupiedStandWithoutTakingItsBottles(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        supplies(companion, 1);
        stand(h).setItem(0, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(STAND));
        task.start(context);
        h.succeedWhen(() -> {
            h.assertValueEqual(task.tick(context), TaskStatus.FAILED, task.resultSummary());
            h.assertTrue(task.resultSummary().contains("occupied"), task.resultSummary());
            h.assertValueEqual(potions(stand(h), Potions.AWKWARD), 1, "the existing bottle stayed in the stand");
            h.assertValueEqual(potions(companion.inventory(), Potions.WATER), 1, "the carried water bottle stayed");
            h.assertValueEqual(count(companion.inventory(), "minecraft:blaze_powder"), 2, "no blaze powder used");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void namesMissingFuelBeforeMovingInputs(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        companion.inventory().addItem(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        companion.inventory().addItem(new ItemStack(Items.NETHER_WART));
        companion.inventory().addItem(new ItemStack(Items.BLAZE_POWDER));
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(STAND));
        task.start(context);
        h.succeedWhen(() -> {
            h.assertValueEqual(task.tick(context), TaskStatus.FAILED, task.resultSummary());
            h.assertTrue(task.resultSummary().contains("fuel"), task.resultSummary());
            h.assertValueEqual(potions(companion.inventory(), Potions.WATER), 1, "water bottle untouched");
            h.assertValueEqual(count(companion.inventory(), "minecraft:nether_wart"), 1, "nether wart untouched");
            h.assertValueEqual(count(companion.inventory(), "minecraft:blaze_powder"), 1, "ingredient untouched");
            h.assertTrue(stand(h).isEmpty(), "the refusal left the station empty");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void namesMissingWaterAndLeavesInputsUntouched(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        companion.inventory().addItem(new ItemStack(Items.NETHER_WART));
        companion.inventory().addItem(new ItemStack(Items.BLAZE_POWDER, 2));
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(STAND));
        task.start(context);
        h.succeedWhen(() -> {
            h.assertValueEqual(task.tick(context), TaskStatus.FAILED, task.resultSummary());
            h.assertTrue(task.resultSummary().contains("water bottle"), task.resultSummary());
            h.assertValueEqual(count(companion.inventory(), "minecraft:nether_wart"), 1, "ingredient untouched");
            h.assertTrue(stand(h).isEmpty(), "the refusal left the station empty");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 1000)
    public void cancelReturnsInputsAndCannotReplayTheOldBatch(GameTestHelper h) {
        CompanionEntity companion = setup(h);
        supplies(companion, 1);
        TaskContext context = new TaskContext(companion, h.getLevel());
        BrewTask cancelled = new BrewTask("minecraft:strength", 1, h.absolutePos(STAND));
        cancelled.start(context);
        BrewTask[] retry = {null};
        h.succeedWhen(() -> {
            if (retry[0] == null) {
                h.assertValueEqual(cancelled.tick(context), TaskStatus.RUNNING, cancelled.resultSummary());
                h.assertValueEqual(potions(stand(h), Potions.WATER), 1, "batch staged before cancellation");
                TaskState checkpoint = new TaskState("brew_potion", cancelled.saveData());
                cancelled.stop(context);
                h.assertTrue(stand(h).isEmpty(), "cancel emptied the staged slots");
                h.assertValueEqual(potions(companion.inventory(), Potions.WATER), 1, "cancel returned the water");
                h.assertValueEqual(
                        count(companion.inventory(), "minecraft:nether_wart"), 1, "cancel returned the wart");
                h.assertValueEqual(
                        count(companion.inventory(), "minecraft:blaze_powder"), 2, "cancel returned the fuel");
                BrewTask stale =
                        (BrewTask) BrewCheckpoints.brew(checkpoint, context).orElseThrow();
                stale.start(context);
                h.assertValueEqual(stale.tick(context), TaskStatus.FAILED, "stale checkpoint cannot claim output");
                retry[0] = new BrewTask("minecraft:strength", 1, h.absolutePos(STAND));
                retry[0].start(context);
            }
            h.assertValueEqual(retry[0].tick(context), TaskStatus.SUCCESS, retry[0].resultSummary());
            assertResult(h, companion, retry[0], 1, 0);
        });
    }

    private static CompanionEntity setup(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(STAND, Blocks.BREWING_STAND);
        return h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
    }

    private static BrewingStandBlockEntity stand(GameTestHelper h) {
        return (BrewingStandBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STAND));
    }

    private static void supplies(CompanionEntity companion, int bottles) {
        for (int i = 0; i < bottles; i++) {
            companion.inventory().addItem(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        }
        companion.inventory().addItem(new ItemStack(Items.NETHER_WART));
        companion.inventory().addItem(new ItemStack(Items.BLAZE_POWDER, 2));
    }

    private static void assertResult(
            GameTestHelper h, CompanionEntity companion, BrewTask task, int carried, int left) {
        h.assertValueEqual(potions(companion.inventory(), Potions.STRENGTH), carried, "strength bottles carried");
        h.assertValueEqual(potions(stand(h), Potions.STRENGTH), left, "strength bottles at the original stand");
        h.assertValueEqual(count(companion.inventory(), "minecraft:nether_wart"), 0, "one wart consumed");
        h.assertValueEqual(
                count(companion.inventory(), "minecraft:blaze_powder"), 0, "one ingredient and one fuel item used");
        h.assertTrue(stand(h).getItem(3).isEmpty(), "neither ingredient remained in the stand");
        String summary = task.resultSummary();
        h.assertTrue(summary.contains("brewed " + (carried + left) + " strength"), summary);
        h.assertTrue(summary.contains(carrriedText(carried)), summary);
        BlockPos pos = h.absolutePos(STAND);
        h.assertTrue(
                left == 0
                        || summary.contains(
                                left + " in the stand at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()),
                summary);
        h.assertTrue(summary.contains("1 nether wart") && summary.contains("1 blaze powder"), summary);
        h.assertTrue(summary.contains("2 fuel uses"), summary);
    }

    private static String carrriedText(int count) {
        return count + " in my inventory";
    }

    private static int potions(Container container, Holder<Potion> potion) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.is(potion)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int count(Container container, String item) {
        return InventoryOps.count(container, ItemMatcher.parse(item).orElseThrow());
    }
}
