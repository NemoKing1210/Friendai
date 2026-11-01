package dev.friendai.features.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a {@link BondLedger}: every hidden field the ledger keeps, in the shape it is persisted
 * with. The component names are the persisted field names, so a codec or JSON writer needs no mapping table.
 *
 * <p>Reading is defensive: at most {@link #MAX_REASONS} reasons are kept (newest first, as the ledger orders them),
 * blank reasons and null peaks are dropped, negative counters and peaks are clamped to zero, and both collections are
 * copied so a later caller cannot mutate a restored ledger through them.
 */
public record BondState(int points, int lastDay, int awardedToday, List<String> reasons, Map<String, Integer> peaks) {
    /** A fresh ledger with no deeds yet. */
    public static final BondState EMPTY = new BondState(0, 0, 0, List.of(), Map.of());

    /** The ledger keeps only the newest few reasons, and so does the persisted snapshot. */
    public static final int MAX_REASONS = 3;

    public BondState {
        points = Math.max(0, points);
        awardedToday = Math.max(0, awardedToday);
        List<String> keptReasons = new ArrayList<>(Math.min(MAX_REASONS, reasons == null ? 0 : reasons.size()));
        if (reasons != null) {
            for (String reason : reasons) {
                if (keptReasons.size() == MAX_REASONS) {
                    break;
                }
                if (reason != null && !reason.isBlank()) {
                    keptReasons.add(reason);
                }
            }
        }
        reasons = List.copyOf(keptReasons);
        Map<String, Integer> keptPeaks = new LinkedHashMap<>();
        if (peaks != null) {
            peaks.forEach((kind, value) -> {
                if (kind != null && !kind.isBlank() && value != null) {
                    keptPeaks.put(kind, Math.max(0, value));
                }
            });
        }
        peaks = Collections.unmodifiableMap(keptPeaks);
    }
}
