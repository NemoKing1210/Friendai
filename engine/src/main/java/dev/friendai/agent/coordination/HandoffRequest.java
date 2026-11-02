package dev.friendai.agent.coordination;

import java.util.UUID;

/**
 * One owner-authorized offer to move an exact item quantity to another owner's companion at a named meeting
 * point. It carries no access level and authorizes only that item, quantity and point; acceptance is not authority
 * over the recipient's other work.
 */
public record HandoffRequest(
        UUID fromOwner,
        UUID toOwner,
        String item,
        int count,
        String dimension,
        int x,
        int y,
        int z,
        long expiresAtTick) {
    public static final int MAX_COUNT = 64;
    public static final int MAX_TEXT = 64;
    public static final int COORDINATE_MAX = 30_000_000;

    public HandoffRequest {
        if (fromOwner == null || toOwner == null) throw new IllegalArgumentException("owners are required");
        if (fromOwner.equals(toOwner)) throw new IllegalArgumentException("a handoff needs two owners");
        if (item == null || item.isBlank()) throw new IllegalArgumentException("item is required");
        item = item.strip();
        if (item.length() > MAX_TEXT) throw new IllegalArgumentException("item is too long");
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count must be 1.." + MAX_COUNT);
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
        dimension = dimension.strip();
        if (Math.abs(x) > COORDINATE_MAX || Math.abs(y) > 1_000 || Math.abs(z) > COORDINATE_MAX) {
            throw new IllegalArgumentException("meeting point is outside the world");
        }
        if (expiresAtTick <= 0) throw new IllegalArgumentException("expiry is required");
    }

    public boolean expired(long now) {
        return now >= expiresAtTick;
    }
}
