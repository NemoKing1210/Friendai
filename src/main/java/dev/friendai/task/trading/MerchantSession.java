package dev.friendai.task.trading;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.trading.TradeAuthority;
import dev.friendai.trading.TradeItem;
import dev.friendai.trading.TradeOffer;
import dev.friendai.trading.TradeOrder;
import dev.friendai.trading.TradePolicy;
import dev.friendai.trading.TradeProgress;
import dev.friendai.trading.TraderRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One-use, one-game-tick wrapper around a real vanilla MerchantMenu. */
public final class MerchantSession implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/trading");

    public record Outcome(boolean traded, String reason, TradeProgress progress) {
        public Outcome {
            if (reason == null || progress == null) throw new IllegalArgumentException("invalid trade outcome");
        }
    }

    private record Piece(int inventorySlot, int count, ItemStack identity) {}

    private record Payment(ItemStack escrow, List<Piece> pieces) {
        private Payment {
            escrow = escrow.copy();
            pieces = List.copyOf(pieces);
        }
    }

    private record PaymentPlan(Payment first, Payment second, Map<String, Integer> totals) {}

    private record Selected(int slot, ItemStack stack) {}

    private final CompanionEntity companion;
    private final Entity target;
    private final Merchant merchant;
    private final MerchantMenu menu;
    private final TradeOrder order;
    private final TradeAuthority authority;
    private final dev.friendai.trading.TradeBook book;
    private final MerchantLeases leases;
    private final TradeSessionGuard guard;
    private final long openedTick;
    private final UUID pricingActor;
    private boolean attempted;
    private boolean closed;

    MerchantSession(
            CompanionEntity companion,
            Entity target,
            Merchant merchant,
            MerchantMenu menu,
            TradeOrder order,
            TradeAuthority authority,
            dev.friendai.trading.TradeBook book,
            MerchantLeases leases,
            TradeSessionGuard guard,
            long openedTick,
            UUID pricingActor) {
        this.companion = java.util.Objects.requireNonNull(companion);
        this.target = java.util.Objects.requireNonNull(target);
        this.merchant = java.util.Objects.requireNonNull(merchant);
        this.menu = java.util.Objects.requireNonNull(menu);
        this.order = java.util.Objects.requireNonNull(order);
        this.authority = java.util.Objects.requireNonNull(authority);
        this.book = java.util.Objects.requireNonNull(book);
        this.leases = java.util.Objects.requireNonNull(leases);
        this.guard = java.util.Objects.requireNonNull(guard);
        this.openedTick = openedTick;
        this.pricingActor = java.util.Objects.requireNonNull(pricingActor);
    }

    /** Performs at most one current-price vanilla result take and closes the menu before returning. */
    public Outcome tradeOnce(TradeOrder requested) {
        TradeProgress current = currentProgress();
        PaymentPlan reservedPayment = null;
        MerchantOffer reservedOffer = null;
        int reservedUses = -1;
        try {
            if (attempted) return new Outcome(false, "session already attempted a transaction", current);
            attempted = true;
            if (requested == null || !order.equals(requested))
                return new Outcome(false, "session order changed", current);
            String invalid = invalidReason(currentTick());
            if (invalid != null) return new Outcome(false, invalid, current);

            ServerLevel level = (ServerLevel) companion.level();
            MerchantOffers offers = merchant.getOffers();
            List<TradeOffer> live = TraderAdapters.offers(
                    offers, companion.hands().player().getUUID(), level.getGameTime(), level.registryAccess());
            if (!pricingActor.equals(companion.hands().player().getUUID())) {
                return new Outcome(false, "companion pricing actor changed", current);
            }
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).ref().equals(order.offerRef())) indices.add(i);
            }
            if (indices.size() != 1) return new Outcome(false, "selected live offer is missing or ambiguous", current);

            int selectedIndex = indices.getFirst();
            TradeOffer currentQuote = live.get(selectedIndex);
            long semanticMatches = live.stream()
                    .filter(offer -> offer.baseInputs().equals(currentQuote.baseInputs())
                            && offer.output().equals(currentQuote.output()))
                    .count();
            if (semanticMatches != 1) {
                return new Outcome(false, "selected live offer is ambiguous after merchant reordering", current);
            }
            if (!pricingActor.equals(currentQuote.pricingActor())) {
                return new Outcome(false, "live offer belongs to another pricing actor", current);
            }
            MerchantOffer offer = offers.get(selectedIndex);
            if (offer.isOutOfStock()) return new Outcome(false, "selected offer is exhausted", current);

            PaymentPlan payment = planPayment(companion.inventory(), offer, order, level.registryAccess());
            if (payment == null)
                return new Outcome(false, "required vanilla inputs are unavailable or protected", current);
            Map<String, Integer> available = inventoryTotals(companion.inventory());
            boolean outputFits = outputFits(companion.inventory(), payment, offer.getResult());
            TradePolicy.Decision decision =
                    TradePolicy.check(order, currentQuote, available, current, outputFits, level.getGameTime());
            if (!decision.allowed()) return new Outcome(false, decision.reason(), current);
            if (!authority.reserve(order, payment.totals())) {
                return new Outcome(false, "trade authority denied the next payment reservation", current);
            }
            reservedPayment = payment;
            reservedOffer = offer;
            reservedUses = offer.getUses();
            return execute(offer, selectedIndex, payment, current, level.registryAccess());
        } catch (RuntimeException failure) {
            if (reservedPayment != null && reservedOffer != null && reservedOffer.getUses() == reservedUses) {
                authority.releaseReservation(order, reservedPayment.totals());
            }
            LOGGER.warn("Trade order {} stopped safely: {}", order.orderId(), safeMessage(failure));
            return new Outcome(false, safeMessage(failure), current);
        } finally {
            close();
        }
    }

    private Outcome execute(
            MerchantOffer offer,
            int selectedIndex,
            PaymentPlan plan,
            TradeProgress previous,
            HolderLookup.Provider registries) {
        List<ItemStack> removed = new ArrayList<>(2);
        int usesBefore = offer.getUses();
        ItemStack taken = ItemStack.EMPTY;
        Inventory handsInventory = companion.hands().player().getInventory();
        int matchingOutputBefore = countExact(handsInventory, offer.getResult());
        try {
            ItemStack first = remove(plan.first());
            removed.add(first);
            ItemStack second = plan.second() == null ? ItemStack.EMPTY : remove(plan.second());
            removed.add(second);
            menu.setSelectionHint(selectedIndex);
            menu.getSlot(0).set(first.copy());
            if (!second.isEmpty()) {
                menu.getSlot(1).set(second.copy());
            }

            if (merchant.getTradingPlayer() != companion.hands().player()
                    || !menu.stillValid(companion.hands().player())) {
                throw new IllegalStateException("merchant session was lost before result pickup");
            }
            ItemStack expected = offer.getResult().copy();
            if (!menu.getSlot(2).mayPickup(companion.hands().player())
                    || !ItemStack.matches(menu.getSlot(2).getItem(), expected)) {
                throw new IllegalStateException("vanilla result slot does not match the selected offer");
            }
            taken = menu.quickMoveStack(companion.hands().player(), 2);
            int usesAfter = offer.getUses();
            boolean completed = usesAfter == usesBefore + 1
                    && ItemStack.matches(taken, expected)
                    && menu.getSlot(0).getItem().isEmpty()
                    && menu.getSlot(1).getItem().isEmpty();
            if (!completed) {
                if (usesAfter > usesBefore) {
                    TradeProgress uncertain = reconciled(
                            previous,
                            plan,
                            observedOutput(taken, handsInventory, matchingOutputBefore, expected, registries),
                            TradeProgress.State.UNCERTAIN,
                            "vanilla use advanced but result reconciliation was incomplete",
                            registries);
                    return new Outcome(false, uncertain.reason(), uncertain);
                }
                authority.releaseReservation(order, plan.totals());
                return new Outcome(false, "vanilla did not complete exactly one transaction", previous);
            }
            TradeItem receipt = TradeItemCodec.encode(taken, registries);
            TradeProgress next =
                    reconciled(previous, plan, receipt, nextState(order, previous, receipt), "", registries);
            return new Outcome(true, next.reason(), next);
        } catch (RuntimeException failure) {
            if (offer.getUses() > usesBefore) {
                TradeItem receipt;
                try {
                    receipt =
                            observedOutput(taken, handsInventory, matchingOutputBefore, offer.getResult(), registries);
                } catch (RuntimeException undecodableOutput) {
                    receipt = null;
                }
                TradeProgress uncertain = reconciled(
                        previous,
                        plan,
                        receipt,
                        TradeProgress.State.UNCERTAIN,
                        "vanilla use advanced before result retrieval was verified",
                        registries);
                return new Outcome(false, uncertain.reason(), uncertain);
            }
            authority.releaseReservation(order, plan.totals());
            return new Outcome(false, "trade failed before vanilla completion: " + safeMessage(failure), previous);
        } finally {
            // Return only any escrow that was never placed in the menu; closeMenu returns the rest.
            boolean tradeAdvanced = offer.getUses() > usesBefore;
            ItemStack firstNotPlaced = tradeAdvanced || removed.isEmpty()
                    ? ItemStack.EMPTY
                    : unplaced(removed.getFirst(), menu.getSlot(0).getItem());
            ItemStack secondNotPlaced = tradeAdvanced || removed.size() < 2
                    ? ItemStack.EMPTY
                    : unplaced(removed.get(1), menu.getSlot(1).getItem());
            try {
                close();
            } finally {
                if (!firstNotPlaced.isEmpty()) InventoryOps.insertOrDrop(companion, firstNotPlaced);
                if (!secondNotPlaced.isEmpty()) InventoryOps.insertOrDrop(companion, secondNotPlaced);
            }
        }
    }

    private TradeItem observedOutput(
            ItemStack taken,
            Inventory handsInventory,
            int before,
            ItemStack expected,
            HolderLookup.Provider registries) {
        if (taken != null && !taken.isEmpty() && ItemStack.isSameItemSameComponents(taken, expected)) {
            return TradeItemCodec.encode(taken, registries);
        }
        int after = countExact(handsInventory, expected);
        return after > before ? TradeItemCodec.encode(expected.copyWithCount(after - before), registries) : null;
    }

    private TradeProgress reconciled(
            TradeProgress previous,
            PaymentPlan plan,
            TradeItem receipt,
            TradeProgress.State state,
            String reason,
            HolderLookup.Provider registries) {
        Map<String, Integer> spent = new HashMap<>(previous.spent());
        plan.totals().forEach((item, count) -> spent.merge(item, count, Math::addExact));
        List<TradeItem> received;
        try {
            received = appendReceipt(previous.received(), receipt);
        } catch (IllegalStateException capacity) {
            state = TradeProgress.State.UNCERTAIN;
            reason = "trade completed but its receipt exceeded the bounded progress record";
            received = previous.received();
        }
        TradeProgress next = new TradeProgress(
                order.orderId(), state, spent, received, Math.incrementExact(previous.completedTrades()), reason);
        authority.record(next);
        try {
            book.record(next);
            TraderRecordRefresh.refresh(book, target, merchant, pricingActor, currentTick(), registries);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not persist merchant progress for order {}: {}", order.orderId(), safeMessage(failure));
        }
        return next;
    }

    private static TradeProgress.State nextState(TradeOrder order, TradeProgress previous, TradeItem receipt) {
        int received = previous.receivedCount(receipt.itemId(), receipt.componentsJson());
        received = Math.addExact(received, receipt.count());
        if (received >= order.desiredOutputCount()) return TradeProgress.State.COMPLETED;
        return TradeProgress.State.RUNNING;
    }

    private static List<TradeItem> appendReceipt(List<TradeItem> previous, TradeItem receipt) {
        if (receipt == null) return previous;
        var result = new ArrayList<>(previous);
        int remaining = receipt.count();
        for (int i = 0; i < result.size() && remaining > 0; i++) {
            TradeItem existing = result.get(i);
            if (!existing.itemId().equals(receipt.itemId())
                    || !existing.componentsJson().equals(receipt.componentsJson())
                    || existing.count() >= TradeItem.MAX_COUNT) continue;
            int add = Math.min(remaining, TradeItem.MAX_COUNT - existing.count());
            result.set(i, new TradeItem(existing.itemId(), existing.componentsJson(), existing.count() + add));
            remaining -= add;
        }
        while (remaining > 0) {
            if (result.size() >= TradeProgress.MAX_RECEIPTS) {
                throw new IllegalStateException("trade receipt summary capacity is exhausted");
            }
            int add = Math.min(remaining, TradeItem.MAX_COUNT);
            result.add(new TradeItem(receipt.itemId(), receipt.componentsJson(), add));
            remaining -= add;
        }
        return List.copyOf(result);
    }

    private static ItemStack unplaced(ItemStack escrow, ItemStack menuSlot) {
        if (escrow.isEmpty()) return ItemStack.EMPTY;
        int placed = !menuSlot.isEmpty() && ItemStack.isSameItemSameComponents(menuSlot, escrow)
                ? Math.min(escrow.getCount(), menuSlot.getCount())
                : 0;
        int missing = escrow.getCount() - placed;
        return missing == 0 ? ItemStack.EMPTY : escrow.copyWithCount(missing);
    }

    private TradeProgress currentProgress() {
        return authority
                .progress(order.orderId())
                .orElseGet(() ->
                        new TradeProgress(order.orderId(), TradeProgress.State.AUTHORIZED, Map.of(), List.of(), 0, ""));
    }

    private static PaymentPlan planPayment(
            Container inventory, MerchantOffer offer, TradeOrder order, HolderLookup.Provider registries) {
        var pool = new ArrayList<Selected>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            pool.add(new Selected(slot, stack.copy()));
        }
        Payment first = select(pool, offer.getItemCostA(), offer.getCostA().getCount(), order, registries);
        if (first == null) return null;
        Payment second = null;
        if (offer.getItemCostB().isPresent()) {
            second = select(
                    pool, offer.getItemCostB().orElseThrow(), offer.getCostB().getCount(), order, registries);
            if (second == null) return null;
        }
        Map<String, Integer> totals = new HashMap<>();
        mergeCost(totals, first.escrow());
        if (second != null) mergeCost(totals, second.escrow());
        return new PaymentPlan(first, second, Map.copyOf(totals));
    }

    /** Chooses one exact component identity that can fill the vanilla input slot. */
    private static Payment select(
            List<Selected> pool, ItemCost cost, int required, TradeOrder order, HolderLookup.Provider registries) {
        var groups = new ArrayList<List<Selected>>();
        for (Selected candidate : pool) {
            ItemStack stack = candidate.stack();
            if (stack.isEmpty() || !cost.test(stack) || !TradeItemCodec.mayPay(stack, order, registries)) continue;
            List<Selected> group = groups.stream()
                    .filter(existing -> ItemStack.isSameItemSameComponents(
                            existing.getFirst().stack(), stack))
                    .findFirst()
                    .orElse(null);
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(candidate);
        }
        for (List<Selected> group : groups) {
            int total = group.stream()
                    .mapToInt(selected -> selected.stack().getCount())
                    .sum();
            if (total < required) continue;
            ItemStack escrow = group.getFirst().stack().copyWithCount(required);
            int remaining = required;
            var pieces = new ArrayList<Piece>();
            for (Selected selected : group) {
                if (remaining == 0) break;
                int take = Math.min(remaining, selected.stack().getCount());
                pieces.add(new Piece(selected.slot(), take, selected.stack().copyWithCount(1)));
                selected.stack().shrink(take);
                remaining -= take;
            }
            return new Payment(escrow, pieces);
        }
        return null;
    }

    private ItemStack remove(Payment payment) {
        if (payment == null) return ItemStack.EMPTY;
        for (Piece piece : payment.pieces()) {
            ItemStack live = companion.inventory().getItem(piece.inventorySlot());
            if (piece.count() < 1
                    || piece.identity().isEmpty()
                    || live.getCount() < piece.count()
                    || !ItemStack.isSameItemSameComponents(live, piece.identity())) {
                throw new IllegalStateException("companion inventory changed after payment preflight");
            }
        }
        ItemStack removed = ItemStack.EMPTY;
        try {
            for (Piece piece : payment.pieces()) {
                ItemStack stack = companion.inventory().removeItem(piece.inventorySlot(), piece.count());
                if (stack.isEmpty()
                        || !ItemStack.isSameItemSameComponents(stack, piece.identity())
                        || stack.getCount() != piece.count()) {
                    if (!stack.isEmpty()) InventoryOps.insertOrDrop(companion, stack);
                    throw new IllegalStateException("companion inventory changed while taking payment");
                }
                if (removed.isEmpty()) removed = stack;
                else removed.grow(stack.getCount());
            }
            return removed;
        } catch (RuntimeException failure) {
            if (!removed.isEmpty()) InventoryOps.insertOrDrop(companion, removed);
            throw failure;
        }
    }

    private static boolean outputFits(Container original, PaymentPlan payment, ItemStack result) {
        SimpleContainer simulation = new SimpleContainer(original.getContainerSize());
        for (int i = 0; i < original.getContainerSize(); i++)
            simulation.setItem(i, original.getItem(i).copy());
        if (!consume(simulation, payment.first())
                || (payment.second() != null && !consume(simulation, payment.second()))) {
            return false;
        }
        return simulation.addItem(result.copy()).isEmpty();
    }

    private static boolean consume(Container inventory, Payment payment) {
        if (payment == null) return true;
        for (Piece piece : payment.pieces()) {
            ItemStack slot = inventory.getItem(piece.inventorySlot());
            if (slot.getCount() < piece.count() || !ItemStack.isSameItemSameComponents(slot, piece.identity()))
                return false;
            slot.shrink(piece.count());
            if (slot.isEmpty()) inventory.setItem(piece.inventorySlot(), ItemStack.EMPTY);
        }
        return true;
    }

    private static Map<String, Integer> inventoryTotals(Container inventory) {
        Map<String, Integer> totals = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            totals.merge(item, stack.getCount(), Math::addExact);
        }
        return Map.copyOf(totals);
    }

    private static void mergeCost(Map<String, Integer> totals, ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        totals.merge(id, stack.getCount(), Math::addExact);
    }

    private static int countExact(Container inventory, ItemStack expected) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack actual = inventory.getItem(i);
            if (!actual.isEmpty() && ItemStack.isSameItemSameComponents(actual, expected)) {
                count = Math.addExact(count, actual.getCount());
            }
        }
        return count;
    }

    String invalidReason(long nowTick) {
        if (closed) return "merchant session is closed";
        if (nowTick != openedTick) return "merchant session crossed a game tick";
        if (nowTick > order.deadlineTick()) return "trade order expired";
        if (!companion.isAlive()) return "companion is no longer alive";
        if (!order.owner().equals(companion.ownerUuid())) return "companion owner changed";
        if (companion.level() != target.level()) return "merchant changed dimension";
        if (!target.isAlive()) return "merchant is no longer alive";
        if (companion.executionCoordinator().paused()
                || companion.executionCoordinator().suspended()) return "companion task was paused";
        if (!leases.heldBy(order.trader(), order.orderId())) return "merchant lease expired";
        if (merchant.getTradingPlayer() != companion.hands().player()) return "merchant customer changed";
        if (!(companion.level() instanceof ServerLevel level)
                || level.getServer().getPlayerList().getPlayer(order.owner()) == null) {
            return "trade owner is no longer online";
        }
        if (!menu.stillValid(companion.hands().player())) return "merchant menu is no longer valid";
        return null;
    }

    void abort(String reason) {
        if (closed) return;
        LOGGER.debug("Closing trade session for order {}: {}", order.orderId(), safeMessage(reason));
        close();
    }

    private long currentTick() {
        return companion.level() instanceof ServerLevel level
                ? level.getServer().overworld().getGameTime()
                : -1;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            companion.hands().closeMenu();
        } finally {
            try {
                guard.unregister(this);
            } finally {
                try {
                    leases.release(order.trader(), order.orderId());
                } finally {
                    book.release(order.trader());
                }
            }
        }
    }

    private static String safeMessage(Throwable failure) {
        return safeMessage(failure == null ? "trade interrupted" : failure.getMessage());
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "trade interrupted" : message;
    }

    /** Keeps offer observations and transaction history in the owner book without exposing registry objects. */
    private static final class TraderRecordRefresh {
        private static void refresh(
                dev.friendai.trading.TradeBook book,
                Entity target,
                Merchant merchant,
                UUID actor,
                long tick,
                HolderLookup.Provider registries) {
            TraderRecord record = TraderAdapters.verified(target, merchant.getOffers(), actor, tick, registries);
            book.observe(record);
        }
    }
}
