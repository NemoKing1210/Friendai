package dev.friendai.crafting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The stations a companion placed and still owns, each leased for a while after it was last used. Crafting several
 * things in a row is several tasks, and taking the table back between them makes the companion place it again for the
 * next item; a lease lets the following craft reuse the standing station and sends the companion back for it only
 * when no craft followed.
 *
 * <p>Pure: positions travel as opaque {@code long} keys the caller makes from a block position.
 */
public final class StationLease {
    /** How long a station stays out after it was last used, before the companion comes back for it. */
    public static final int GRACE_TICKS = 600;

    /** A station whose grace ran out: the companion should take it back. */
    public record Due(Station station, long posKey) {}

    private record Held(long posKey, long deadline) {}

    private final Map<Station, Held> held = new LinkedHashMap<>();

    /** Leases {@code posKey} for the station from {@code now}; calling it again renews the lease. */
    public void keep(Station station, long posKey, long now) {
        held.put(station, new Held(posKey, now + GRACE_TICKS));
    }

    /** The position still leased for the station, empty when the lease ran out or there is none. */
    public OptionalLong held(Station station, long now) {
        Held lease = held.get(station);
        return lease != null && now < lease.deadline() ? OptionalLong.of(lease.posKey()) : OptionalLong.empty();
    }

    /** The first station whose lease ran out, in placement order. It stays due until {@link #release} is called. */
    public Optional<Due> due(long now) {
        for (Map.Entry<Station, Held> entry : held.entrySet()) {
            if (now >= entry.getValue().deadline()) {
                return Optional.of(new Due(entry.getKey(), entry.getValue().posKey()));
            }
        }
        return Optional.empty();
    }

    /** Drops the station: it was taken back, or left behind on purpose. */
    public void release(Station station) {
        held.remove(station);
    }

    public boolean isEmpty() {
        return held.isEmpty();
    }
}
