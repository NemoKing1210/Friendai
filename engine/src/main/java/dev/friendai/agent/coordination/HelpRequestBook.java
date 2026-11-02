package dev.friendai.agent.coordination;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded in-memory help requests, one open request per owner pair. Acceptance removes the request and returns it;
 * the caller may then perform only that named help. Nothing here grants or stores an access level.
 */
public final class HelpRequestBook {
    public static final int MAX_OPEN = 4;

    private final Map<Pair, HelpRequest> open = new LinkedHashMap<>();

    /** Adds a request if neither owner already has an open request with the other and the cap allows it. */
    public boolean request(HelpRequest request) {
        if (request == null) return false;
        Pair pair = new Pair(request.fromOwner(), request.toOwner());
        if (open.containsKey(pair) || open.size() >= MAX_OPEN) {
            return false;
        }
        open.put(pair, request);
        return true;
    }

    public Optional<HelpRequest> open(UUID fromOwner, UUID toOwner, long now) {
        HelpRequest request = open.get(new Pair(fromOwner, toOwner));
        return request == null || request.expired(now) ? Optional.empty() : Optional.of(request);
    }

    /** Consumes an open request; only that named help is authorized. */
    public Optional<HelpRequest> accept(UUID fromOwner, UUID toOwner, long now) {
        HelpRequest request = open.remove(new Pair(fromOwner, toOwner));
        return request == null || request.expired(now) ? Optional.empty() : Optional.of(request);
    }

    public boolean decline(UUID fromOwner, UUID toOwner) {
        return open.remove(new Pair(fromOwner, toOwner)) != null;
    }

    /** Removes every expired request; returns how many left. */
    public int expire(long now) {
        int before = open.size();
        Iterator<Map.Entry<Pair, HelpRequest>> iterator = open.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now)) {
                iterator.remove();
            }
        }
        return before - open.size();
    }

    public List<HelpRequest> openFor(UUID toOwner, long now) {
        List<HelpRequest> requests = new ArrayList<>();
        for (HelpRequest request : open.values()) {
            if (request.toOwner().equals(toOwner) && !request.expired(now)) {
                requests.add(request);
            }
        }
        return List.copyOf(requests);
    }

    public int size() {
        return open.size();
    }

    public void clear() {
        open.clear();
    }

    private record Pair(UUID from, UUID to) {}
}
