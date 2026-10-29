package dev.friendai.features.trading.minecraft.trading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-tick backstop for sessions whose task or menu lifecycle was interrupted. */
public final class TradeSessionGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/trading");
    private final MerchantLeases leases;
    private final Set<MerchantSession> sessions = Collections.newSetFromMap(new IdentityHashMap<>());

    public TradeSessionGuard(MerchantLeases leases) {
        this.leases = java.util.Objects.requireNonNull(leases);
    }

    synchronized void register(MerchantSession session) {
        sessions.add(java.util.Objects.requireNonNull(session));
    }

    synchronized void unregister(MerchantSession session) {
        sessions.remove(session);
    }

    /** Called independently of task ticking; expired leases and invalid sessions are closed on the server thread. */
    public void tick(long nowTick) {
        if (nowTick < 0) return;
        leases.expire(nowTick);
        ArrayList<MerchantSession> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(sessions);
        }
        for (MerchantSession session : snapshot) {
            try {
                String reason = session.invalidReason(nowTick);
                if (reason != null) session.abort(reason);
            } catch (RuntimeException failure) {
                LOGGER.warn("Trade session validation failed; closing it", failure);
                try {
                    session.abort("session validation failed");
                } catch (RuntimeException closeFailure) {
                    LOGGER.warn("Could not close invalid trade session", closeFailure);
                }
            }
        }
    }

    /** Closes all live sessions during server shutdown or lifecycle reset. */
    public void closeAll(String reason) {
        ArrayList<MerchantSession> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(sessions);
        }
        String safeReason = reason == null || reason.isBlank() ? "trade session guard closed" : reason;
        for (MerchantSession session : snapshot) {
            try {
                session.abort(safeReason);
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not close trade session during shutdown", failure);
            }
        }
    }
}
