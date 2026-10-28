package dev.friendai.task.trading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runtime-only exclusive leases for merchant entities. All methods are server-thread safe. */
public final class MerchantLeases {
    /** A session normally lives for one game tick; this grace covers one missed guard callback. */
    public static final long MAX_IDLE_TICKS = 1;

    public record Lease(UUID trader, UUID orderId, long lastRenewedTick) {
        public Lease {
            if (trader == null || orderId == null || lastRenewedTick < 0) {
                throw new IllegalArgumentException("invalid merchant lease");
            }
        }
    }

    private final Map<UUID, Lease> byTrader = new HashMap<>();

    /** Acquires a free lease or renews the same order's live lease. */
    public synchronized boolean acquire(UUID trader, UUID orderId, long tick) {
        if (trader == null || orderId == null || tick < 0) return false;
        expireOne(trader, tick);
        Lease existing = byTrader.get(trader);
        if (existing != null && !existing.orderId().equals(orderId)) return false;
        if (existing != null && tick < existing.lastRenewedTick()) return false;
        byTrader.put(trader, new Lease(trader, orderId, tick));
        return true;
    }

    /** Renews only the lease held by this exact order and only with a nondecreasing game tick. */
    public synchronized boolean renew(UUID trader, UUID orderId, long tick) {
        if (trader == null || orderId == null || tick < 0) return false;
        expireOne(trader, tick);
        Lease existing = byTrader.get(trader);
        if (existing == null || !existing.orderId().equals(orderId) || tick < existing.lastRenewedTick()) return false;
        byTrader.put(trader, new Lease(trader, orderId, tick));
        return true;
    }

    /** A stale session cannot release a lease that has since been acquired by another order. */
    public synchronized boolean release(UUID trader, UUID orderId) {
        if (trader == null || orderId == null) return false;
        Lease existing = byTrader.get(trader);
        if (existing == null || !existing.orderId().equals(orderId)) return false;
        byTrader.remove(trader);
        return true;
    }

    /** Expires idle leases and returns the exact pairs so guards can close only their owners' sessions. */
    public synchronized List<Lease> expire(long tick) {
        if (tick < 0) return List.of();
        var expired = new ArrayList<Lease>();
        for (var iterator = byTrader.entrySet().iterator(); iterator.hasNext(); ) {
            Lease lease = iterator.next().getValue();
            if (tick >= lease.lastRenewedTick() && tick - lease.lastRenewedTick() > MAX_IDLE_TICKS) {
                expired.add(lease);
                iterator.remove();
            }
        }
        return List.copyOf(expired);
    }

    public synchronized boolean heldBy(UUID trader, UUID orderId) {
        Lease lease = byTrader.get(trader);
        return lease != null && lease.orderId().equals(orderId);
    }

    private void expireOne(UUID trader, long tick) {
        Lease lease = byTrader.get(trader);
        if (lease != null && tick >= lease.lastRenewedTick() && tick - lease.lastRenewedTick() > MAX_IDLE_TICKS) {
            byTrader.remove(trader);
        }
    }
}
