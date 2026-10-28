package dev.friendai.features.trading;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BarterPolicyTest {
    private static final UUID OWNER = new UUID(0, 1);

    @Test
    void acceptedGoldStaysSpentAndCannotBeReservedTwice() {
        var authority = new TradeAuthority(OWNER);
        Caller caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var order = authority.authorizeBarter(
                caller,
                new BarterOrder(
                        UUID.randomUUID(), OWNER, caller.tradingScope(), new UUID(0, 2), 1, Optional.empty(), 0, 100));
        assertTrue(authority.reserveBarter(order, 1));
        authority.recordBarter(new BarterProgress(
                order.orderId(), 1, Set.of(), List.of(), BarterProgress.State.GOLD_ACCEPTED, "accepted"));
        assertFalse(authority.reserveBarter(order, 1));
        var item = new TradeItem("minecraft:gravel", "{}", 4);
        var target = new TradeQuery(TradeQuery.Direction.BUY, "minecraft:gravel", "{}", 40);
        var progress = new BarterProgress(
                UUID.randomUUID(),
                1,
                Set.of(UUID.randomUUID()),
                List.of(item),
                BarterProgress.State.PARTIAL,
                "partially collected");
        assertEquals(4, progress.collectedCount(target));
    }

    @Test
    void targetShapeAndGoldLimitsAreValidated() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BarterOrder(
                        UUID.randomUUID(), OWNER, UUID.randomUUID(), UUID.randomUUID(), 65, Optional.empty(), 0, 100));
        var target = new TradeQuery(TradeQuery.Direction.SELL, "minecraft:gravel", "{}", 40);
        assertThrows(
                IllegalArgumentException.class,
                () -> new BarterOrder(
                        UUID.randomUUID(),
                        OWNER,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        Optional.of(target),
                        0,
                        100));
    }
}
