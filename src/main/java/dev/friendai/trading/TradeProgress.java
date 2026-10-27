package dev.friendai.trading;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cumulative observed progress, never a prediction of receipts or permission to pay again. */
public record TradeProgress(UUID orderId, State state, Map<String, Integer> spent,
                            List<TradeItem> received, int completedTrades, String reason) {
    public enum State { AUTHORIZED, RUNNING, COMPLETED, PARTIAL, INTERRUPTED, UNCERTAIN, FAILED }
    public static final int MAX_INPUT_KINDS = 256;
    public static final int MAX_RECEIPTS = 2048;

    public TradeProgress {
        if (orderId == null || state == null || completedTrades < 0 || spent == null
                || spent.size() > MAX_INPUT_KINDS || received == null || received.size() > MAX_RECEIPTS) {
            throw new IllegalArgumentException("invalid trade progress");
        }
        reason = TradeItem.requireText(reason, 512);
        long total = 0;
        for (var entry : spent.entrySet()) {
            TradeItem.requireId(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 0) throw new IllegalArgumentException("invalid spend");
            total += entry.getValue();
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("cumulative spend overflow");
        }
        spent = Map.copyOf(spent);
        received = List.copyOf(received);
        total = 0;
        for (var item : received) {
            total += item.count();
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("cumulative receipt overflow");
        }
    }
}
