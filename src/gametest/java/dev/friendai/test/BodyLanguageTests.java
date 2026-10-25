package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.movement.Emotes;
import dev.friendai.task.builtin.EmoteTask;
import dev.friendai.task.builtin.RelativeMoveTask;
import dev.friendai.task.builtin.SitTask;
import dev.friendai.task.builtin.ThrowItemTask;
import dev.friendai.task.builtin.TurnTask;
import dev.friendai.task.builtin.WaitTask;
import dev.friendai.task.inventory.ItemMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/** Turning, gestures, sneaking, sitting, short relative moves and tossing items. One floor band per test. */
public class BodyLanguageTests {
    @GameTest(maxTicks = 100)
    public void turnReachesRequestedYaw(GameTestHelper h) {
        int floor = 140;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.setYRot(0.0f);
        c.taskRunner().start(TurnTask.toYaw(90.0f, "west"), "turn");
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
        c.taskRunner().start(new EmoteTask(Emotes.Kind.BOW), "emote");
        boolean[] crouched = {false};
        h.succeedWhen(() -> {
            if (c.getPose() == Pose.CROUCHING) {
                crouched[0] = true;
            }
            h.assertTrue(crouched[0], "a bow never crouched");
            h.assertTrue(c.taskRunner().current().isEmpty() || !"emote".equals(c.taskRunner().current().get().type()),
                    "emote still running");
            h.assertFalse(c.body().sneaking(), "sneak was not released after the emote");
        });
    }

    @GameTest(maxTicks = 100)
    public void sitCrouchesUntilStopped(GameTestHelper h) {
        int floor = 160;
        GameTestSupport.platformAt(h, floor, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 3);
        c.taskRunner().start(new SitTask(), "sit");
        h.runAfterDelay(10, () -> {
            h.assertTrue(c.body().sitting(), "not sitting");
            h.assertTrue(c.getPose() == Pose.CROUCHING, "pose was " + c.getPose());
            c.taskRunner().stop("test");
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
        c.taskRunner().start(new RelativeMoveTask("step", "stepping forward 5 blocks", companion ->
                new Vec3(start.x + 5.0, start.y, start.z), false), "step");
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
        c.taskRunner().start(new ThrowItemTask(apple, 2, null, ""), "throw_item");
        h.succeedWhen(() -> {
            int thrown = h.getLevel().getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(12.0)).stream()
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
        c.taskRunner().start(new WaitTask(20), "wait");
        h.runAfterDelay(40, () -> {
            h.assertTrue(c.taskRunner().current().isEmpty() || !"wait".equals(c.taskRunner().current().get().type()),
                    "still waiting");
            h.succeed();
        });
    }
}
