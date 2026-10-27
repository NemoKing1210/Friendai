package dev.friendai.trading;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.trading.TradeProgress.State;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeAuthorityTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID GUEST = new UUID(0, 2);
    private static final UUID TRADER = new UUID(0, 3);

    @Test void onlyAnActualOwnerPlayerBatchCreatesAScope() {
        var authority = new TradeAuthority(OWNER);
        var owner = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        assertNotNull(owner.tradingScope());

        assertNull(authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), false).tradingScope());
        assertNull(authority.begin(new Caller(GUEST, "guest", AccessLevel.GUEST), true).tradingScope());
        assertNull(authority.begin(new Caller(GUEST, "trusted", AccessLevel.TRUSTED), true).tradingScope());
    }

    @Test void deferredCallerKeepsCapturedScopeAcrossLaterOwnerTurn() {
        var authority = new TradeAuthority(OWNER);
        var deferred = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var captured = deferred.tradingScope();
        var laterTurn = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        assertNotEquals(captured, laterTurn.tradingScope());

        var bound = authority.authorize(deferred, order(deferred.tradingScope(), budget(10), 4));
        assertEquals(captured, bound.scopeId());
    }

    @Test void manualRetryReusesTheCapturedScopeInsteadOfTheLaterTurn() {
        var authority = new TradeAuthority(OWNER);
        var originalCaller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var originalOrder = order(originalCaller.tradingScope(), budget(10), 4);
        var bound = authority.authorize(originalCaller, originalOrder);
        var laterCaller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);

        var retried = authority.authorize(originalCaller, originalOrder);
        assertEquals(bound.scopeId(), retried.scopeId());
        assertNotEquals(laterCaller.tradingScope(), retried.scopeId());
        assertTrue(authority.reserve(retried, Map.of("minecraft:emerald", 3)));
    }

    @Test void oneScopeAggregatesReservationsAndReconcilesOnlyActualSpend() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var budget = budget(10);
        var first = authority.authorize(caller, order(caller.tradingScope(), budget, 6));
        var second = authority.authorize(caller, order(caller.tradingScope(), budget, 6));

        assertTrue(authority.reserve(first, Map.of("minecraft:emerald", 6)));
        assertFalse(authority.reserve(second, Map.of("minecraft:emerald", 5)));
        authority.record(new TradeProgress(first.orderId(), State.COMPLETED,
                Map.of("minecraft:emerald", 4), List.of(new TradeItem("minecraft:book", "{}", 1)), 1, ""));
        assertTrue(authority.reserve(second, Map.of("minecraft:emerald", 6)));
        assertFalse(authority.reserve(second, Map.of("minecraft:emerald", 1)));
    }

    @Test void actualSpendCannotExceedNarrowOrderBudgetEvenWhenSharedBudgetAllowsIt() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var sharedCeilingOrder = authority.authorize(caller, order(caller.tradingScope(), budget(10), 1));
        var narrowOrder = authority.authorize(caller, order(caller.tradingScope(), budget(5), 1));

        authority.record(new TradeProgress(narrowOrder.orderId(), State.FAILED,
                Map.of("minecraft:emerald", 6), List.of(), 0, "observed payment exceeded this order's budget"));

        assertFalse(authority.reserve(sharedCeilingOrder, Map.of("minecraft:emerald", 1)),
                "an individual order overrun must fail closed for the whole scope");
        assertThrows(RuntimeException.class,
                () -> authority.authorize(caller, order(caller.tradingScope(), budget(1), 1)));
    }

    @Test void aScopeMayNarrowItsFirstBudgetButCannotIncreaseIt() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        authority.authorize(caller, order(caller.tradingScope(), budget(10), 1));

        assertDoesNotThrow(() -> authority.authorize(caller, order(caller.tradingScope(), budget(8), 1)));
        assertThrows(RuntimeException.class, () -> authority.authorize(caller, order(caller.tradingScope(), budget(11), 1)));
    }

    @Test void foreignAndInvalidatedScopesNeverAuthorizeAndRestartHasNoAuthority() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var authorized = authority.authorize(caller, order(caller.tradingScope(), budget(10), 1));
        var foreign = new Caller(GUEST, "guest", AccessLevel.GUEST, caller.tradingScope());
        assertThrows(RuntimeException.class, () -> authority.authorize(foreign,
                order(caller.tradingScope(), budget(10), 1)));

        authority.invalidateAll("server restarted");
        assertThrows(RuntimeException.class, () -> authority.authorize(caller,
                order(caller.tradingScope(), budget(10), 1)));
        var restarted = new TradeAuthority(OWNER);
        assertThrows(RuntimeException.class, () -> restarted.authorize(caller, authorized));
    }

    @Test void authorizationRejectsForgedScopeIdsAndMalformedRequestedCaps() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        assertThrows(RuntimeException.class, () -> authority.authorize(caller,
                order(UUID.randomUUID(), budget(10), 1)));
        assertThrows(IllegalArgumentException.class, () -> new TradeBudget(Map.of(), Map.of(), Map.of()));
    }

    @Test void terminalUncertainProgressCannotReserveAnotherPayment() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var order = authority.authorize(caller, order(caller.tradingScope(), budget(10), 1));
        assertTrue(authority.reserve(order, Map.of("minecraft:emerald", 3)));
        authority.record(new TradeProgress(order.orderId(), State.UNCERTAIN,
                Map.of(), List.of(), 0, "result was ambiguous"));
        authority.record(new TradeProgress(order.orderId(), State.RUNNING,
                Map.of(), List.of(), 0, "late update"));

        assertFalse(authority.reserve(order, Map.of("minecraft:emerald", 1)));
        var other = authority.authorize(caller, order(caller.tradingScope(), budget(10), 1));
        assertFalse(authority.reserve(other, Map.of("minecraft:emerald", 8)),
                "unresolved payment reservation must still consume the shared ceiling");
    }

    @Test void reauthorizationCanNarrowBudgetButCannotChangeOrderTargetOrSelection() {
        var authority = new TradeAuthority(OWNER);
        var caller = authority.begin(new Caller(OWNER, "owner", AccessLevel.OWNER), true);
        var original = order(caller.tradingScope(), budget(10), 4);
        authority.authorize(caller, original);

        var narrower = new TradeOrder(original.orderId(), original.owner(), original.scopeId(), original.trader(),
                original.offerRef(), original.observationRevision(), original.desiredOutputCount(), budget(8),
                original.allowedPaymentFingerprints(), original.allowOvershoot(), original.allowPartial(), 90);
        assertEquals(8, authority.authorize(caller, narrower).budget().inputCaps().get("minecraft:emerald"));

        var retargeted = new TradeOrder(original.orderId(), original.owner(), original.scopeId(), original.trader(),
                "different-offer", original.observationRevision(), original.desiredOutputCount(), budget(8),
                original.allowedPaymentFingerprints(), original.allowOvershoot(), original.allowPartial(), 90);
        assertThrows(RuntimeException.class, () -> authority.authorize(caller, retargeted));
    }

    private static TradeBudget budget(int emeralds) {
        return new TradeBudget(Map.of("minecraft:emerald", emeralds), Map.of(), Map.of());
    }

    private static TradeOrder order(UUID scope, TradeBudget budget, int desired) {
        return new TradeOrder(UUID.randomUUID(), OWNER, scope, TRADER, "offer", 1, desired, budget,
                Set.of(), false, true, 100);
    }
}
