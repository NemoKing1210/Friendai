package dev.friendai.features.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trust changes that wait for the owner's confirmation. One request per owner; a new one replaces the
 * old one, and an unconfirmed request expires after {@link #TTL_MILLIS}. Pure Java; ids increase per run.
 */
public final class PendingTrust {
    public static final long TTL_MILLIS = 60_000;

    private final Map<UUID, TrustRequest> requests = new HashMap<>();
    private int nextId = 1;

    /** Replaces any earlier request of the same owner. Returns the new request. */
    public TrustRequest open(UUID owner, UUID target, String name, boolean trust, long now) {
        TrustRequest request = new TrustRequest(nextId++, owner, target, name, trust, now + TTL_MILLIS);
        requests.put(owner, request);
        return request;
    }

    /** The owner's request, or empty when they have none or it has expired. */
    public Optional<TrustRequest> current(UUID owner, long now) {
        TrustRequest request = requests.get(owner);
        if (request == null) {
            return Optional.empty();
        }
        if (isExpired(request, now)) {
            requests.remove(owner);
            return Optional.empty();
        }
        return Optional.of(request);
    }

    /** Removes and returns the request if the id matches and it has not expired. */
    public Optional<TrustRequest> take(UUID owner, int id, long now) {
        TrustRequest request = requests.get(owner);
        if (request == null || request.id() != id) {
            return Optional.empty();
        }
        requests.remove(owner);
        return isExpired(request, now) ? Optional.empty() : Optional.of(request);
    }

    /** Removes and returns the owner's current request. */
    public Optional<TrustRequest> takeCurrent(UUID owner, long now) {
        Optional<TrustRequest> request = current(owner, now);
        request.ifPresent(r -> requests.remove(owner));
        return request;
    }

    /** Expired requests, removed; for expiry notices. */
    public List<TrustRequest> expire(long now) {
        List<TrustRequest> expired = new ArrayList<>();
        Iterator<Map.Entry<UUID, TrustRequest>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            TrustRequest request = iterator.next().getValue();
            if (isExpired(request, now)) {
                expired.add(request);
                iterator.remove();
            }
        }
        return expired;
    }

    private static boolean isExpired(TrustRequest request, long now) {
        return request.expiresAtMillis() <= now;
    }
}
