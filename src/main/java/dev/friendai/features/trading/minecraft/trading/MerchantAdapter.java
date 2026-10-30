package dev.friendai.features.trading.minecraft.trading;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.TradeBookRepository;
import dev.friendai.features.trading.TradeOrder;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.task.interact.CompanionHands;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffers;

/** Server adapter for merchant inspection and opening a single validated vanilla transaction session. */
public final class MerchantAdapter {
    private final TradeBookRepository store;
    private final MerchantLeases leases;
    private final TradeSessionGuard guard;

    public MerchantAdapter(TradeBookRepository store, MerchantLeases leases, TradeSessionGuard guard) {
        this.store = java.util.Objects.requireNonNull(store);
        this.leases = java.util.Objects.requireNonNull(leases);
        this.guard = java.util.Objects.requireNonNull(guard);
    }

    /** Reads a merchant's full inventory of offers by opening its real menu as the companion's fake player. */
    public Optional<TraderRecord> inspect(CompanionEntity companion, Entity target, long nowTick) {
        if (!validPair(companion, target, nowTick) || !(target instanceof Merchant merchant)) return Optional.empty();
        nowTick = serverTick(companion);
        UUID owner = companion.ownerUuid();
        if (owner == null) return Optional.empty();
        TradeBook book = store.book(owner);
        CompanionHands hands = companion.hands();
        UUID actor = hands.player().getUUID();
        book.activatePricingActor(actor);

        TraderRecord presence = TraderAdapters.presence(target, nowTick);
        book.observe(presence);
        if (merchant.getTradingPlayer() != null) return Optional.empty();

        UUID inspectLease = UUID.randomUUID();
        if (!leases.acquire(target.getUUID(), inspectLease, nowTick)) return Optional.empty();
        try {
            MerchantMenu menu = hands.openMerchant(target);
            if (menu == null || merchant.getTradingPlayer() != hands.player()) return Optional.empty();
            MerchantOffers offers = merchant.getOffers();
            TraderRecord verified = TraderAdapters.verified(
                    target, offers, actor, nowTick, ((ServerLevel) companion.level()).registryAccess());
            if (!book.observe(verified)) return Optional.empty();
            return book.trader(target.getUUID());
        } finally {
            try {
                hands.closeMenu();
            } finally {
                leases.release(target.getUUID(), inspectLease);
            }
        }
    }

    /**
     * Opens only the exact remembered merchant and quote actor. This method rereads the loaded entity's complete
     * offer list and semantic ref before returning a session; it never opens by remembered list index.
     */
    public MerchantSession openSession(
            CompanionEntity companion, Entity target, TradeOrder order, TradeAuthority authority, long nowTick) {
        if (order == null
                || authority == null
                || !validPair(companion, target, nowTick)
                || !(target instanceof Merchant merchant)) {
            throw new IllegalStateException("merchant or trade order is unavailable");
        }
        nowTick = serverTick(companion);
        UUID owner = companion.ownerUuid();
        if (owner == null || !owner.equals(order.owner()) || !target.getUUID().equals(order.trader())) {
            throw new IllegalStateException("trade order owner or merchant does not match the live target");
        }
        if (nowTick > order.deadlineTick()) throw new IllegalStateException("trade order expired");

        TradeBook book = store.book(owner);
        CompanionHands hands = companion.hands();
        UUID actor = hands.player().getUUID();
        book.activatePricingActor(actor);
        TraderRecord remembered = book.trader(order.trader())
                .orElseThrow(() -> new IllegalStateException("merchant has not been inspected"));
        if (authority.progress(order.orderId()).isEmpty() && remembered.revision() != order.observationRevision()) {
            throw new IllegalStateException("merchant observation changed after order authorization");
        }
        if (TraderAdapters.quoteForActor(book, order.trader(), order.offerRef(), actor)
                .isEmpty()) {
            throw new IllegalStateException("offer quote is stale or belongs to another pricing actor");
        }
        if (merchant.getTradingPlayer() != null) {
            book.observe(TraderAdapters.presence(target, nowTick));
            throw new IllegalStateException("merchant is busy with another player");
        }
        if (!leases.acquire(order.trader(), order.orderId(), nowTick)) {
            throw new IllegalStateException("merchant is leased by another order");
        }

        boolean retained = false;
        boolean registered = false;
        try {
            book.retain(order.trader());
            retained = true;
            MerchantMenu menu = hands.openMerchant(target);
            if (menu == null || merchant.getTradingPlayer() != hands.player() || !menu.stillValid(hands.player())) {
                throw new IllegalStateException("merchant menu could not be acquired");
            }

            List<dev.friendai.features.trading.TradeOffer> live = TraderAdapters.offers(
                    merchant.getOffers(), actor, nowTick, ((ServerLevel) companion.level()).registryAccess());
            long matches = live.stream()
                    .filter(offer -> offer.ref().equals(order.offerRef()))
                    .count();
            if (matches != 1) throw new IllegalStateException("selected offer disappeared or is ambiguous");
            var selected = live.stream()
                    .filter(offer -> offer.ref().equals(order.offerRef()))
                    .findFirst()
                    .orElseThrow();
            long semanticMatches = live.stream()
                    .filter(offer -> offer.baseInputs().equals(selected.baseInputs())
                            && offer.output().equals(selected.output()))
                    .count();
            if (semanticMatches != 1)
                throw new IllegalStateException("selected offer became ambiguous after reordering");
            if (live.stream()
                    .filter(offer -> offer.ref().equals(order.offerRef()))
                    .anyMatch(offer -> !actor.equals(offer.pricingActor()))) {
                throw new IllegalStateException("live offer was priced for another actor");
            }

            MerchantSession session = new MerchantSession(
                    companion, target, merchant, menu, order, authority, book, leases, guard, nowTick, actor);
            guard.register(session);
            registered = true;
            return session;
        } finally {
            // A failed setup must release only its own acquired state; hands.closeMenu is safe if vanilla ownership
            // changed.
            if (!registered) {
                try {
                    hands.closeMenu();
                } finally {
                    try {
                        if (retained) book.release(order.trader());
                    } finally {
                        leases.release(order.trader(), order.orderId());
                    }
                }
            }
        }
    }

    private static boolean validPair(CompanionEntity companion, Entity target, long nowTick) {
        return companion != null
                && target != null
                && nowTick >= 0
                && companion.isAlive()
                && target.isAlive()
                && !companion.level().isClientSide()
                && companion.level() instanceof ServerLevel
                && companion.level() == target.level();
    }

    private static long serverTick(CompanionEntity companion) {
        return ((ServerLevel) companion.level()).getServer().overworld().getGameTime();
    }
}
