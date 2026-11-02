package dev.friendai.agent.coordination;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded in-memory handoff offers, one per owner pair. Taking an offer consumes it; a failed transfer leaves the
 * items where they are and does not silently replay the offer.
 */
public final class HandoffBook {
    public static final int MAX_OPEN = 4;

    private final Map<Pair, HandoffRequest> open = new LinkedHashMap<>();

    public boolean offer(HandoffRequest request) {
        if (request == null) return false;
        Pair pair = new Pair(request.fromOwner(), request.toOwner());
        if (open.containsKey(pair) || open.size() >= MAX_OPEN) return false;
        open.put(pair, request);
        return true;
    }

    public Optional<HandoffRequest> open(UUID fromOwner, UUID toOwner, long now) {
        HandoffRequest request = open.get(new Pair(fromOwner, toOwner));
        return request == null || request.expired(now) ? Optional.empty() : Optional.of(request);
    }

    /** Consumes an offer; the caller must revalidate the meeting, items and capacity before moving anything. */
    public Optional<HandoffRequest> take(UUID fromOwner, UUID toOwner, long now) {
        HandoffRequest request = open.remove(new Pair(fromOwner, toOwner));
        return request == null || request.expired(now) ? Optional.empty() : Optional.of(request);
    }

    public boolean decline(UUID fromOwner, UUID toOwner) {
        return open.remove(new Pair(fromOwner, toOwner)) != null;
    }

    public int expire(long now) {
        int before = open.size();
        Iterator<Map.Entry<Pair, HandoffRequest>> iterator = open.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now)) iterator.remove();
        }
        return before - open.size();
    }

    public List<HandoffRequest> openFor(UUID toOwner, long now) {
        List<HandoffRequest> result = new ArrayList<>();
        for (HandoffRequest request : open.values()) {
            if (request.toOwner().equals(toOwner) && !request.expired(now)) result.add(request);
        }
        return List.copyOf(result);
    }

    public int size() {
        return open.size();
    }

    public void clear() {
        open.clear();
    }

    private record Pair(UUID from, UUID to) {}
}
