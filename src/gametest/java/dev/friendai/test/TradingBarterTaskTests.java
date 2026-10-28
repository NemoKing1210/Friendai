package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.BarterTask;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.trading.BarterOrder;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-side order gate and single-payment behavior for piglin barter. */
public final class TradingBarterTaskTests {
    @GameTest(maxTicks = 40)
    public void oneAuthorizedOrderPaysAtMostOneInFlightIngot(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setNoAi(true);
        var owner = GameTestSupport.mockPlayerAt(h, 12, 2, 2);
        UUID ownerId = owner.getUUID();
        companion.setOwner(ownerId);
        companion.inventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        Piglin piglin = h.spawn(EntityTypes.PIGLIN, 3, 2, 2);
        piglin.setNoAi(true);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        Caller caller = state.sessions()
                .tradeAuthorityFor(ownerId)
                .begin(new Caller(ownerId, "owner", AccessLevel.OWNER), true);
        BarterOrder order = state.sessions()
                .tradeAuthorityFor(ownerId)
                .authorizeBarter(
                        caller,
                        new BarterOrder(
                                UUID.randomUUID(),
                                ownerId,
                                caller.tradingScope(),
                                piglin.getUUID(),
                                1,
                                Optional.empty(),
                                0,
                                h.getLevel().getGameTime() + 30));
        var context = new TaskContext(companion, h.getLevel());
        BarterTask task = new BarterTask(order);
        task.start(context);
        TaskStatus first = task.tick(context);
        h.assertTrue(first == TaskStatus.RUNNING, "first authorized payment should enter pending-output wait");
        h.assertTrue(
                companion.inventory().countItem(Items.GOLD_INGOT) == 1, "exactly one gold ingot should be accepted");
        for (int i = 0; i < 5; i++) {
            task.tick(context);
            h.assertTrue(
                    companion.inventory().countItem(Items.GOLD_INGOT) == 1,
                    "no second payment is allowed while the first output is pending");
        }
        task.stop(context);
        h.succeed();
    }
}
