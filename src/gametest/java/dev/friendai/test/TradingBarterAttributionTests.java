package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.trading.minecraft.trading.PiglinBarterAdapter;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Proves attribution only from real vanilla piglin-owned item entities. */
public final class TradingBarterAttributionTests {
    @GameTest(maxTicks = 2_400)
    public void realVanillaBarterOutputsAreAttributedToTheExactPiglin(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setNoAi(true);
        var owner = GameTestSupport.mockPlayerAt(h, 12, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.inventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        Piglin piglin = h.spawn(EntityTypes.PIGLIN, 3, 2, 2);
        piglin.setTarget(null);
        Piglin otherPiglin = h.spawn(EntityTypes.PIGLIN, 8, 2, 2);
        otherPiglin.setNoAi(true);

        ItemEntity before = new ItemEntity(h.getLevel(), 7, 2, 2, new ItemStack(Items.BREAD));
        before.setThrower(piglin);
        h.getLevel().addFreshEntity(before);
        var tracker = GameTestSupport.runtime(h.getLevel().getServer()).barterDropTracker();
        UUID attempt = UUID.randomUUID();
        long started = h.getLevel().getGameTime();
        PiglinBarterAdapter adapter = new PiglinBarterAdapter(tracker);
        h.assertTrue(companion.owner().isPresent(), "fixture owner is not online");
        h.assertTrue(piglin.isAlive() && !piglin.isBaby(), "fixture piglin is not a living adult");
        h.assertTrue(piglin.getTarget() == null && !piglin.isDancing(), "fixture piglin is busy");
        h.assertTrue(!piglin.getMainHandItem().is(Items.GOLD_INGOT), "fixture piglin is already admiring gold");
        h.assertTrue(companion.distanceToSqr(piglin) <= 16.0, "fixture piglin is out of barter reach");
        h.assertTrue(!tracker.hasAttemptFor(piglin.getUUID()), "fixture piglin already has an attempt");
        h.assertTrue(adapter.canStart(companion, piglin.getUUID()), "eligible adult piglin was rejected");
        tracker.begin(attempt, piglin.getUUID(), started);
        ItemEntity unrelated = new ItemEntity(h.getLevel(), 7, 2, 2, new ItemStack(Items.BREAD));
        unrelated.setThrower(otherPiglin);
        h.getLevel().addFreshEntity(unrelated);
        h.assertTrue(
                adapter.payOne(companion, piglin.getUUID(), attempt),
                "vanilla piglin interaction did not accept one gold ingot");
        h.assertTrue(
                companion.inventory().countItem(Items.GOLD_INGOT) == 1, "payment did not consume exactly one ingot");
        h.assertTrue(
                !tracker.outputs(attempt).isEmpty(),
                "vanilla interaction did not synchronously emit and attribute its barter response");
        piglin.setNoAi(false);

        class AwaitBarter implements Runnable {
            private final long deadline = started + 2_400;

            @Override
            public void run() {
                var outputIds = tracker.outputs(attempt);
                if (!outputIds.isEmpty()) {
                    h.assertTrue(
                            outputIds.stream()
                                    .noneMatch(id -> id.equals(before.getUUID()) || id.equals(unrelated.getUUID())),
                            "pre-existing or another piglin's item was attributed");
                    for (UUID outputId : outputIds) {
                        Entity output = h.getLevel().getEntity(outputId);
                        h.assertTrue(output instanceof ItemEntity, "tracked output entity disappeared or changed type");
                        h.assertTrue(
                                ((ItemEntity) output).getOwner() == piglin,
                                "tracked output was not owned by the exact piglin");
                    }
                    tracker.finish(attempt);
                    h.succeed();
                    return;
                }
                if (h.getLevel().getGameTime() >= deadline) {
                    tracker.finish(attempt);
                    h.fail("vanilla did not emit attributed output before the bounded deadline; off="
                            + piglin.getOffhandItem() + ", main=" + piglin.getMainHandItem()
                            + ", tracker=" + tracker.outputs(attempt) + ", owner=" + before.getOwner());
                    return;
                }
                h.runAfterDelay(20, this);
            }
        }
        h.runAfterDelay(20, new AwaitBarter());
    }
}
