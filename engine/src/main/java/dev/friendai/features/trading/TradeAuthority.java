package dev.friendai.features.trading;

import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Runtime-only, owner-bound authority and reservation ledger. Persisted progress never restores scopes. */
public final class TradeAuthority implements TradingTurnPort {
    public static final String QUEUE_SCOPE_ARG = QueueTradeScope.ARG;
    public static final int MAX_ACTIVE_SCOPES = 256;
    private final UUID owner;
    private final Map<UUID, Scope> scopes = new LinkedHashMap<>();
    private final Map<UUID, TradeOrder> orders = new HashMap<>();
    private final Map<UUID, UUID> orderScopes = new HashMap<>();
    private final Map<UUID, BarterOrder> barterOrders = new HashMap<>();
    private final Map<UUID, UUID> barterScopes = new HashMap<>();

    private static final class Scope {
        long deadlineTick = Long.MAX_VALUE;
        final Set<String> allowedPayments = new HashSet<>();
        TradeBudget ceiling;
        boolean active = true;
        String reason = "";
        final Map<UUID, TradeProgress> progress = new HashMap<>();
        final Map<UUID, BarterProgress> barterProgress = new HashMap<>();
        final Map<UUID, Map<String, Integer>> reservations = new HashMap<>();
    }

    public TradeAuthority(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("owner is required");
        this.owner = owner;
    }

    /** Narrow read-only view used by owner-only obtain planning; never creates or widens a scope. */
    public synchronized Optional<TradeBudget> budgetFor(UUID scopeId) {
        Scope scope = scopes.get(scopeId);
        return scope == null || !scope.active ? Optional.empty() : Optional.ofNullable(scope.ceiling);
    }

    public synchronized boolean permitsPayment(UUID scopeId, String fingerprint) {
        Scope scope = scopes.get(scopeId);
        return scope != null && scope.active && fingerprint != null && scope.allowedPayments.contains(fingerprint);
    }

    public synchronized long deadlineFor(UUID scopeId) {
        Scope scope = scopes.get(scopeId);
        return scope == null || !scope.active ? -1 : scope.deadlineTick;
    }

    @Override
    public synchronized Caller begin(Caller caller, boolean playerInitiated) {
        if (caller == null) throw new IllegalArgumentException("caller is required");
        if (!playerInitiated || caller.access() != AccessLevel.OWNER || !owner.equals(caller.uuid())) {
            return caller.withTradingScope(null);
        }
        UUID id = UUID.randomUUID();
        // The budget arrives with the first structured order, but no order can use the scope before then.
        scopes.put(id, new Scope());
        while (scopes.size() > MAX_ACTIVE_SCOPES) {
            UUID expired = scopes.keySet().iterator().next();
            Scope old = scopes.remove(expired);
            if (old != null) old.reservations.clear();
            orderScopes.entrySet().removeIf(entry -> entry.getValue().equals(expired));
            orders.keySet().removeIf(orderId -> !orderScopes.containsKey(orderId));
            barterScopes.entrySet().removeIf(entry -> entry.getValue().equals(expired));
            barterOrders.keySet().removeIf(orderId -> !barterScopes.containsKey(orderId));
        }
        return caller.withTradingScope(id);
    }

    /** Bind an order to the current owner scope; the returned value is safe to capture in a deferred task. */
    public synchronized TradeOrder authorize(Caller caller, TradeOrder requested) {
        if (caller == null
                || requested == null
                || caller.access() != AccessLevel.OWNER
                || !owner.equals(caller.uuid())
                || caller.tradingScope() == null) {
            throw denied("owner turn has no trading authority");
        }
        if (!owner.equals(requested.owner()) || !caller.tradingScope().equals(requested.scopeId())) {
            throw denied("order scope does not belong to this owner turn");
        }
        Scope scope = scopes.get(caller.tradingScope());
        if (scope == null || !scope.active) throw denied("trading authority expired");

        TradeOrder existing = orders.get(requested.orderId());
        if (existing != null) {
            if (!caller.tradingScope().equals(orderScopes.get(requested.orderId()))) {
                throw denied("order id belongs to another trading scope");
            }
            if (!sameIntent(requested, existing)) {
                throw denied("an existing order id cannot be retargeted");
            }
            // Caller-aware task factories may be evaluated once for a preview and again when queued work starts.
            // Reusing the same intent must keep its original absolute deadline instead of extending it by the
            // ticks elapsed between those factory calls.
            if (requested.deadlineTick() > existing.deadlineTick()) {
                requested = withDeadline(requested, existing.deadlineTick());
            }
            if (!isNarrower(
                    requested, existing.budget(), existing.deadlineTick(), existing.allowedPaymentFingerprints())) {
                throw denied("retry cannot increase its original limits");
            }
            var narrowed = bind(requested, caller.tradingScope());
            orders.put(narrowed.orderId(), narrowed);
            return narrowed;
        }

        if (scope.ceiling == null) {
            scope.ceiling = requested.budget();
            scope.deadlineTick = requested.deadlineTick();
            scope.allowedPayments.addAll(requested.allowedPaymentFingerprints());
        } else if (!isNarrower(requested, scope.ceiling, scope.deadlineTick, scope.allowedPayments)) {
            throw denied("order exceeds this turn's shared budget");
        }
        var bound = bind(requested, caller.tradingScope());
        orders.put(bound.orderId(), bound);
        orderScopes.put(bound.orderId(), caller.tradingScope());
        return bound;
    }

    public synchronized BarterOrder authorizeBarter(Caller caller, BarterOrder requested) {
        if (caller == null
                || requested == null
                || caller.access() != AccessLevel.OWNER
                || !owner.equals(caller.uuid())
                || caller.tradingScope() == null
                || !owner.equals(requested.owner())
                || !caller.tradingScope().equals(requested.scopeId())) {
            throw denied("barter requires this owner's active turn scope");
        }
        Scope scope = scopes.get(caller.tradingScope());
        if (scope == null || !scope.active) throw denied("trading authority expired");
        BarterOrder existing = barterOrders.get(requested.orderId());
        if (existing != null) {
            if (!caller.tradingScope().equals(barterScopes.get(requested.orderId()))
                    || !sameIntent(requested, existing)) {
                throw denied("an existing barter order cannot be retargeted");
            }
            if (requested.maxIngots() > existing.maxIngots()) {
                throw denied("barter retry cannot increase its original limits");
            }
            return bind(
                    requested,
                    caller.tradingScope(),
                    Math.min(requested.maxIngots(), existing.maxIngots()),
                    existing.deadlineTick());
        }
        if (scope.ceiling == null) {
            scope.ceiling = new TradeBudget(Map.of("minecraft:gold_ingot", requested.maxIngots()), Map.of(), Map.of());
        } else {
            int ceiling = scope.ceiling.inputCaps().getOrDefault("minecraft:gold_ingot", 0);
            if (requested.maxIngots() > ceiling) throw denied("barter exceeds this turn's shared gold budget");
        }
        if (requested.deadlineTick() > scope.deadlineTick) throw denied("barter exceeds this turn's shared deadline");
        if (requested.deadlineTick() < scope.deadlineTick) scope.deadlineTick = requested.deadlineTick();
        BarterOrder bound = bind(requested, caller.tradingScope(), requested.maxIngots(), requested.deadlineTick());
        barterOrders.put(bound.orderId(), bound);
        barterScopes.put(bound.orderId(), caller.tradingScope());
        scope.barterProgress.putIfAbsent(
                bound.orderId(),
                new BarterProgress(
                        bound.orderId(), 0, Set.of(), java.util.List.of(), BarterProgress.State.AUTHORIZED, ""));
        return bound;
    }

    public synchronized boolean reserveBarter(BarterOrder order, int ingots) {
        if (order == null || ingots != 1) return false;
        Scope scope = validBarterScope(order);
        if (scope == null) return false;
        BarterProgress progress = scope.barterProgress.get(order.orderId());
        if (progress != null && progress.terminal()) return false;
        try {
            long orderReserved = reservedTotalFor(scope, order.orderId(), "minecraft:gold_ingot");
            long orderSpent = progress == null ? 0 : progress.goldAccepted();
            long total = Math.addExact(
                    Math.addExact(
                            scopeSpent(scope).getOrDefault("minecraft:gold_ingot", 0L),
                            reservedTotal(scope, "minecraft:gold_ingot")),
                    ingots);
            int sharedCap = scope.ceiling.inputCaps().getOrDefault("minecraft:gold_ingot", 0);
            if (orderSpent + orderReserved + ingots > order.maxIngots() || total > sharedCap) return false;
            scope.reservations
                    .computeIfAbsent(order.orderId(), ignored -> new HashMap<>())
                    .merge("minecraft:gold_ingot", ingots, Math::addExact);
            return true;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    public synchronized boolean releaseBarterReservation(BarterOrder order, int ingots) {
        if (order == null || ingots != 1) return false;
        Scope scope = validBarterScope(order);
        if (scope == null) return false;
        Map<String, Integer> reservation = scope.reservations.get(order.orderId());
        if (reservation == null || reservation.getOrDefault("minecraft:gold_ingot", 0) < ingots) return false;
        reservation.compute("minecraft:gold_ingot", (item, held) -> held <= ingots ? null : held - ingots);
        if (reservation.isEmpty()) scope.reservations.remove(order.orderId());
        return true;
    }

    public synchronized void recordBarter(BarterProgress update) {
        if (update == null) return;
        UUID scopeId = barterScopes.get(update.orderId());
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        BarterOrder order = barterOrders.get(update.orderId());
        if (scope == null || order == null || !scope.active) return;
        BarterProgress previous = scope.barterProgress.get(update.orderId());
        if (previous != null
                && (update.goldAccepted() < previous.goldAccepted()
                        || !update.emittedItems().containsAll(previous.emittedItems())
                        || update.collected().size() < previous.collected().size()
                        || (previous.terminal() && !update.terminal()))) return;
        if (update.goldAccepted() > order.maxIngots()) {
            failClosed(scope, "barter exceeded its order gold limit");
            return;
        }
        scope.barterProgress.put(update.orderId(), update);
        Map<String, Long> spent;
        try {
            spent = scopeSpent(scope);
        } catch (ArithmeticException overflow) {
            failClosed(scope, "shared spending totals overflowed");
            return;
        }
        if (spent.getOrDefault("minecraft:gold_ingot", 0L)
                > scope.ceiling.inputCaps().getOrDefault("minecraft:gold_ingot", 0)) {
            failClosed(scope, "barter exceeded the shared gold budget");
            return;
        }
        Map<String, Integer> reservation = scope.reservations.get(update.orderId());
        if (reservation != null) {
            int delta = update.goldAccepted() - (previous == null ? 0 : previous.goldAccepted());
            if (delta > 0)
                reservation.computeIfPresent(
                        "minecraft:gold_ingot", (item, held) -> held <= delta ? null : held - delta);
            if (reservation.isEmpty() || update.terminal()) scope.reservations.remove(update.orderId());
        }
    }

    public synchronized Optional<BarterProgress> barterProgress(UUID orderId) {
        if (orderId == null) return Optional.empty();
        UUID scopeId = barterScopes.get(orderId);
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.barterProgress.get(orderId));
    }

    /** Reserve the next transaction's exact item totals before opening a merchant session. */
    public synchronized boolean reserve(TradeOrder order, Map<String, Integer> payment) {
        if (order == null || payment == null) return false;
        Scope scope = validScope(order);
        if (scope == null) return false;
        TradeProgress recorded = scope.progress.get(order.orderId());
        if (recorded != null && recorded.terminal()) return false;
        var reservation = scope.reservations.computeIfAbsent(order.orderId(), ignored -> new HashMap<>());
        Map<String, Long> spent = scopeSpent(scope);
        Map<String, Integer> orderSpent = scope.progress.containsKey(order.orderId())
                ? scope.progress.get(order.orderId()).spent()
                : Map.of();
        try {
            for (var entry : payment.entrySet()) {
                TradeItem.requireId(entry.getKey());
                int count = entry.getValue() == null ? 0 : entry.getValue();
                if (count < 1) return false;
                Integer sharedCap = scope.ceiling.inputCaps().get(entry.getKey());
                Integer orderCap = order.budget().inputCaps().get(entry.getKey());
                if (sharedCap == null || orderCap == null) return false;
                long pendingOrder = Math.addExact((long) reservation.getOrDefault(entry.getKey(), 0), count);
                long orderTotal = Math.addExact((long) orderSpent.getOrDefault(entry.getKey(), 0), pendingOrder);
                long sharedTotal = Math.addExact(
                        Math.addExact(spent.getOrDefault(entry.getKey(), 0L), reservedTotal(scope, entry.getKey())),
                        count);
                if (orderTotal > orderCap || sharedTotal > sharedCap) return false;
            }
            payment.forEach((item, count) -> reservation.merge(item, count, Math::addExact));
            return true;
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Release an exact reservation only when vanilla did not complete a transaction. */
    public synchronized boolean releaseReservation(TradeOrder order, Map<String, Integer> payment) {
        if (order == null || payment == null || payment.isEmpty()) return false;
        Scope scope = validScope(order);
        if (scope == null) return false;
        TradeProgress recorded = scope.progress.get(order.orderId());
        if (recorded != null && recorded.terminal()) return false;
        Map<String, Integer> reservation = scope.reservations.get(order.orderId());
        if (reservation == null) return false;
        try {
            for (var entry : payment.entrySet()) {
                TradeItem.requireId(entry.getKey());
                Integer count = entry.getValue();
                if (count == null || count < 1 || reservation.getOrDefault(entry.getKey(), 0) < count) return false;
            }
            for (var entry : payment.entrySet()) {
                reservation.compute(
                        entry.getKey(), (item, held) -> held <= entry.getValue() ? null : held - entry.getValue());
            }
            if (reservation.isEmpty()) scope.reservations.remove(order.orderId());
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Runtime progress for an order authorized by this owner ledger; persisted history is not authority. */
    public synchronized Optional<TradeProgress> progress(UUID orderId) {
        if (orderId == null || !orders.containsKey(orderId)) return Optional.empty();
        UUID scopeId = orderScopes.get(orderId);
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.progress.get(orderId));
    }

    /** Reconcile cumulative observed payment; unused reservation is released when an order stops. */
    public synchronized void record(TradeProgress update) {
        if (update == null) return;
        UUID scopeId = orderScopes.get(update.orderId());
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        if (scope == null) return;
        TradeProgress previous = scope.progress.get(update.orderId());
        if (previous != null
                && (!monotonic(previous.spent(), update.spent()) || (previous.terminal() && !update.terminal())))
            return;
        scope.progress.put(update.orderId(), update);

        TradeOrder order = orders.get(update.orderId());
        for (var entry : update.spent().entrySet()) {
            Integer orderCap = order == null ? null : order.budget().inputCaps().get(entry.getKey());
            if (orderCap == null || entry.getValue() > orderCap) {
                failClosed(scope, "actual payment exceeded this order's budget");
                return;
            }
        }

        Map<String, Long> actual;
        try {
            actual = scopeSpent(scope);
        } catch (ArithmeticException overflow) {
            failClosed(scope, "actual payment totals overflowed");
            return;
        }
        for (var entry : actual.entrySet()) {
            Integer cap = scope.ceiling.inputCaps().get(entry.getKey());
            if (cap == null || entry.getValue() > cap) {
                failClosed(scope, "actual payment exceeded the shared budget");
                return;
            }
        }

        Map<String, Integer> reservation = scope.reservations.get(update.orderId());
        if (reservation != null) {
            Map<String, Integer> oldSpend = previous == null ? Map.of() : previous.spent();
            for (var entry : update.spent().entrySet()) {
                int delta = entry.getValue() - oldSpend.getOrDefault(entry.getKey(), 0);
                if (delta > 0)
                    reservation.computeIfPresent(entry.getKey(), (item, held) -> held <= delta ? null : held - delta);
            }
            if (update.state() != TradeProgress.State.UNCERTAIN && update.terminal()) {
                scope.reservations.remove(update.orderId());
            } else if (reservation.isEmpty()) scope.reservations.remove(update.orderId());
        }
    }

    private static void failClosed(Scope scope, String reason) {
        scope.active = false;
        scope.reason = reason;
        scope.reservations.clear();
    }

    /** Revoke every in-memory scope. History remains separately owned by TradeBook. */
    public synchronized void invalidateAll(String reason) {
        String safeReason = reason == null ? "authority invalidated" : TradeItem.requireText(reason, 512);
        for (var scope : scopes.values()) {
            scope.active = false;
            scope.reason = safeReason;
            scope.reservations.clear();
        }
    }

    private Scope validScope(TradeOrder order) {
        if (!owner.equals(order.owner()) || !order.equals(orders.get(order.orderId()))) return null;
        UUID scopeId = orderScopes.get(order.orderId());
        if (scopeId == null || !scopeId.equals(order.scopeId())) return null;
        Scope scope = scopes.get(scopeId);
        return scope != null && scope.active ? scope : null;
    }

    private Scope validBarterScope(BarterOrder order) {
        if (!owner.equals(order.owner()) || !order.equals(barterOrders.get(order.orderId()))) return null;
        UUID scopeId = barterScopes.get(order.orderId());
        if (scopeId == null || !scopeId.equals(order.scopeId())) return null;
        Scope scope = scopes.get(scopeId);
        return scope != null && scope.active ? scope : null;
    }

    private static boolean sameIntent(BarterOrder requested, BarterOrder original) {
        return requested.owner().equals(original.owner())
                && requested.scopeId().equals(original.scopeId())
                && requested.piglin().equals(original.piglin())
                && requested.earlyStopTarget().equals(original.earlyStopTarget())
                && requested.targetCount() == original.targetCount();
    }

    private BarterOrder bind(BarterOrder requested, UUID scopeId, int maxIngots, long deadline) {
        return new BarterOrder(
                requested.orderId(),
                owner,
                scopeId,
                requested.piglin(),
                maxIngots,
                requested.earlyStopTarget(),
                requested.targetCount(),
                deadline);
    }

    private static boolean isNarrower(
            TradeOrder requested, TradeBudget ceiling, long deadline, Set<String> allowedPayments) {
        for (var entry : requested.budget().inputCaps().entrySet()) {
            Integer maximum = ceiling.inputCaps().get(entry.getKey());
            if (maximum == null || entry.getValue() > maximum) return false;
        }
        for (var entry : ceiling.reserves().entrySet()) {
            if (requested.budget().reserves().getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        for (var entry : ceiling.maxUnitCosts().entrySet()) {
            Integer maximum = requested.budget().maxUnitCosts().get(entry.getKey());
            if (maximum == null || maximum > entry.getValue()) return false;
        }
        if (!allowedPayments.containsAll(requested.allowedPaymentFingerprints())) return false;
        return requested.deadlineTick() <= deadline;
    }

    private static boolean sameIntent(TradeOrder requested, TradeOrder original) {
        return requested.owner().equals(original.owner())
                && requested.scopeId().equals(original.scopeId())
                && requested.trader().equals(original.trader())
                && requested.offerRef().equals(original.offerRef())
                && requested.observationRevision() == original.observationRevision()
                && requested.desiredOutputCount() == original.desiredOutputCount()
                && requested.allowedPaymentFingerprints().equals(original.allowedPaymentFingerprints())
                && requested.allowOvershoot() == original.allowOvershoot()
                && requested.allowPartial() == original.allowPartial();
    }

    private TradeOrder bind(TradeOrder requested, UUID scopeId) {
        return new TradeOrder(
                requested.orderId(),
                owner,
                scopeId,
                requested.trader(),
                requested.offerRef(),
                requested.observationRevision(),
                requested.desiredOutputCount(),
                requested.budget(),
                requested.allowedPaymentFingerprints(),
                requested.allowOvershoot(),
                requested.allowPartial(),
                requested.deadlineTick());
    }

    private static TradeOrder withDeadline(TradeOrder requested, long deadlineTick) {
        return new TradeOrder(
                requested.orderId(),
                requested.owner(),
                requested.scopeId(),
                requested.trader(),
                requested.offerRef(),
                requested.observationRevision(),
                requested.desiredOutputCount(),
                requested.budget(),
                requested.allowedPaymentFingerprints(),
                requested.allowOvershoot(),
                requested.allowPartial(),
                deadlineTick);
    }

    private static Map<String, Long> scopeSpent(Scope scope) {
        var total = new HashMap<String, Long>();
        for (var progress : scope.progress.values()) {
            progress.spent().forEach((item, count) -> total.merge(item, (long) count, Math::addExact));
        }
        for (var progress : scope.barterProgress.values()) {
            total.merge("minecraft:gold_ingot", (long) progress.goldAccepted(), Math::addExact);
        }
        return total;
    }

    private static long reservedTotalFor(Scope scope, UUID orderId, String item) {
        Map<String, Integer> reservation = scope.reservations.get(orderId);
        return reservation == null ? 0 : reservation.getOrDefault(item, 0);
    }

    private static long reservedTotal(Scope scope, String item) {
        long total = 0;
        for (var reserve : scope.reservations.values()) total = Math.addExact(total, reserve.getOrDefault(item, 0));
        return total;
    }

    private static boolean monotonic(Map<String, Integer> previous, Map<String, Integer> next) {
        for (var entry : previous.entrySet()) {
            if (next.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        return true;
    }

    private static UserFacingException denied(String reason) {
        return new UserFacingException(UserMessage.of("friendai.trade.authority_denied", reason));
    }
}
