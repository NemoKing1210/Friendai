package dev.friendai.features.companion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Visits at most {@code allowance} keys per call, in a stable order, continuing after the last key it visited.
 *
 * <p>This is the pure half of the companion chunk-restoration sweep: the loader owns the tickets and the world, while
 * this class owns only the fair rotation, so its bound and progress can be tested without a server.
 */
public final class BoundedRotation {
    private static final int UNSET = -1;

    private final int allowance;
    private UUID cursor;
    private int cursorIndex = UNSET;
    private List<UUID> cached = List.of();
    private int cachedSize = UNSET;
    private boolean membershipChanged;

    public BoundedRotation(int allowance) {
        if (allowance <= 0) throw new IllegalArgumentException("allowance must be positive");
        this.allowance = allowance;
    }

    public int allowance() {
        return allowance;
    }

    /**
     * Tells this rotation that membership changed without changing the size (an owner left and another joined). A
     * size change is detected from the collection itself; a same-size change needs this hint or the cached order
     * could name an owner that no longer exists. The next {@code next} rebuilds the order once.
     */
    public void invalidateMembership() {
        membershipChanged = true;
    }

    /** The bounds check plus the visit order for one call: at most {@code allowance} keys, always a subset of {@code keys}. */
    public List<UUID> next(Collection<UUID> keys) {
        if (keys.isEmpty()) {
            cached = List.of();
            cachedSize = 0;
            cursor = null;
            cursorIndex = UNSET;
            membershipChanged = false;
            return List.of();
        }
        if (membershipChanged || !cached.containsAll(keys) || cached.size() != keys.size()) {
            cached = new ArrayList<>(keys);
            cached.sort(Comparator.naturalOrder());
            cachedSize = cached.size();
            membershipChanged = false;
            if (cursor != null) {
                // Recover the cursor by key, not by a stored index: the rebuilt order can shift around it.
                cursorIndex = UNSET;
                for (int index = 0; index < cached.size(); index++) {
                    if (cached.get(index).equals(cursor)) {
                        cursorIndex = index;
                        break;
                    }
                }
            }
        }
        // A pass runs from the cursor to the end of the order. When the end is reached the next pass starts over, so
        // no key is visited twice inside one pass and a pass never exceeds the allowance.
        int from = cursorIndex == UNSET ? 0 : cursorIndex + 1;
        if (from >= cached.size()) {
            from = 0;
        }
        int count = Math.min(allowance, cached.size() - from);
        List<UUID> batch = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = from + offset;
            batch.add(cached.get(index));
            cursorIndex = index;
        }
        cursor = cached.get(cursorIndex);
        return batch;
    }
}
