package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.interaction.minecraft.task.EmoteTask;
import dev.friendai.features.interaction.minecraft.task.SitTask;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.features.navigation.minecraft.task.RelativeMoveTask;
import dev.friendai.features.navigation.minecraft.task.TurnTask;
import dev.friendai.features.navigation.movement.Emotes;
import dev.friendai.task.TaskContext;
import dev.friendai.task.builtin.ThrowItemTask;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BreakAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Turning, gestures, sneaking, sitting, short relative moves and tossing items. One floor band per test. */
public class BodyLanguageTests {
    @GameTest(maxTicks = 100)
    public void turnReachesRequestedYaw(GameTestHelper h) {
        int floor = 140;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.setYRot(0.0f);
        c.executionCoordinator().start(TurnTask.toYaw(90.0f, "west"), "turn");
        h.succeedWhen(() -> {
            float diff = Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYRot() - 90.0f));
            h.assertTrue(diff < 2.0f, "yaw was " + c.getYRot());
        });
    }

    @GameTest(maxTicks = 100)
    public void emoteFinishesAndSneakClears(GameTestHelper h) {
        int floor = 150;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.executionCoordinator().start(new EmoteTask(Emotes.Kind.BOW), "emote");
        boolean[] crouched = {false};
        h.succeedWhen(() -> {
            if (c.getPose() == Pose.CROUCHING) {
                crouched[0] = true;
            }
            h.assertTrue(crouched[0], "a bow never crouched");
            h.assertTrue(
                    c.executionCoordinator().current().isEmpty()
                            || !"emote"
                                    .equals(c.executionCoordinator()
                                            .current()
                                            .get()
                                            .type()),
                    "emote still running");
            h.assertFalse(c.body().sneaking(), "sneak was not released after the emote");
        });
    }

    @GameTest(maxTicks = 100)
    public void sitCrouchesUntilStopped(GameTestHelper h) {
        int floor = 160;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.executionCoordinator().start(new SitTask(), "sit");
        h.runAfterDelay(10, () -> {
            h.assertTrue(c.body().sitting(), "not sitting");
            h.assertTrue(c.getPose() == Pose.CROUCHING, "pose was " + c.getPose());
            c.executionCoordinator().stop("test");
        });
        h.runAfterDelay(30, () -> {
            h.assertFalse(c.body().sitting(), "still sitting after stop");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void stepMovesRelativeToFacing(GameTestHelper h) {
        int floor = 170;
        GameTestSupport.platformAt(h, floor, 20);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setYRot(-90.0f);
        Vec3 start = c.position();
        c.executionCoordinator()
                .start(
                        new RelativeMoveTask(
                                "step",
                                "stepping forward 5 blocks",
                                companion -> new Vec3(start.x + 5.0, start.y, start.z),
                                false),
                        "step");
        h.succeedWhen(() -> {
            double moved = c.position().x - start.x;
            h.assertTrue(moved > 4.0, "only moved " + moved + " blocks east");
        });
    }

    @GameTest(maxTicks = 100)
    public void throwItemDropsItems(GameTestHelper h) {
        int floor = 180;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.inventory().addItem(new ItemStack(Items.APPLE, 3));
        ItemMatcher apple = ItemMatcher.parse("minecraft:apple").orElseThrow();
        c.executionCoordinator().start(new ThrowItemTask(apple, 2, null, ""), "throw_item");
        h.succeedWhen(() -> {
            int thrown = h
                    .getLevel()
                    .getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(12.0))
                    .stream()
                    .map(ItemEntity::getItem)
                    .filter(s -> s.is(Items.APPLE))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            h.assertTrue(thrown == 2, "thrown apples: " + thrown);
        });
    }

    @GameTest(maxTicks = 100)
    public void waitFinishesAfterTicks(GameTestHelper h) {
        int floor = 190;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.executionCoordinator().start(new WaitTask(20), "wait");
        h.runAfterDelay(40, () -> {
            h.assertTrue(
                    c.executionCoordinator().current().isEmpty()
                            || !"wait"
                                    .equals(c.executionCoordinator()
                                            .current()
                                            .get()
                                            .type()),
                    "still waiting");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 160)
    public void brokenBlockIsFacedByBodyAndHead(GameTestHelper h) {
        int floor = 240;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setYRot(0.0F);
        c.setYBodyRot(0.0F);
        c.setYHeadRot(0.0F);
        c.setXRot(0.0F);
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        h.setBlock(2, floor + 1, 1, Blocks.STONE);
        BlockPos target = h.absolutePos(new BlockPos(2, floor + 1, 1));
        TaskContext context = new TaskContext(c, h.getLevel());
        BreakAction action = new BreakAction(target);
        BreakAction.State[] last = {BreakAction.State.WORKING};
        boolean[] broke = {false};
        boolean[] aligned = {false};
        int[] workingTicks = {0};
        h.onEachTick(() -> {
            if (broke[0]) {
                return;
            }
            last[0] = action.tick(context, block -> block.is(Blocks.STONE));
            if (last[0] == BreakAction.State.BROKEN || last[0] == BreakAction.State.GONE) {
                broke[0] = true;
                return;
            }
            if (++workingTicks[0] < 6) {
                return;
            }
            float body = Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYRot() - 180.0F));
            float head = Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYHeadRot() - 180.0F));
            if (body <= 30.0F && head <= 10.0F) {
                aligned[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(broke[0], "the block behind was not broken, last state " + last[0]);
            h.assertTrue(
                    aligned[0],
                    "body/head never settled on the block behind: yaw " + c.getYRot() + ", head " + c.getYHeadRot());
        });
    }

    @GameTest(maxTicks = 80)
    public void placementFacesTheClickedBlock(GameTestHelper h) {
        int floor = 260;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.setYRot(0.0F);
        c.setYBodyRot(0.0F);
        c.setYHeadRot(0.0F);
        c.setXRot(0.0F);
        BlockPos against = h.absolutePos(new BlockPos(3, floor, 1));
        c.hands().place(new ItemStack(Items.STONE), against, Direction.UP);
        h.assertFalse(
                h.getLevel().getBlockState(against.above()).isAir(), "the stone was not placed where the body looked");
        h.succeedWhen(() -> {
            float diff = Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYRot() - 180.0F));
            h.assertTrue(diff <= 15.0F, "the placement left the body facing " + c.getYRot() + ", not the clicked face");
        });
    }

    @GameTest(maxTicks = 60)
    public void throwItemUsesBodyFacing(GameTestHelper h) {
        int floor = 250;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.setYRot(90.0F);
        c.setYBodyRot(90.0F);
        c.setYHeadRot(90.0F);
        c.setXRot(0.0F);
        c.inventory().addItem(new ItemStack(Items.APPLE));
        ItemMatcher apple = ItemMatcher.parse("minecraft:apple").orElseThrow();
        c.executionCoordinator().start(new ThrowItemTask(apple, 1, null, ""), "throw_item");
        h.succeedWhen(() -> {
            for (ItemEntity item : h.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(6.0))) {
                if (item.getOwner() != c || !item.getItem().is(Items.APPLE)) {
                    continue;
                }
                Vec3 velocity = item.getDeltaMovement();
                h.assertTrue(
                        velocity.x < -0.05,
                        "apple left toward " + velocity + " instead of along the body yaw " + c.getYRot());
                return;
            }
            h.fail("no apple was thrown");
        });
    }
}
