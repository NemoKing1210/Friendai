package dev.friendai.debug;

import dev.friendai.foundation.persistence.StoreFailureKind;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The runtime view of the statuses the owner stores already record. It reports a store only when its health changes — a
 * failure, a read-only protection or a recovery — so a full disk is visible once instead of either silently lagging or
 * repeating every flush. Lines carry the store, the owner, the revision and the reason; never a path, a value or an
 * exception message.
 */
public final class StoreHealth {
    /** One store's status for one owner, reduced to the facts a person needs. */
    public record Entry(
            String store,
            UUID owner,
            boolean dirty,
            StoreFailureKind failure,
            StoreReadOnlyReason readOnly,
            long savedRevision) {
        public boolean unhealthy() {
            return failure != null || readOnly != null;
        }

        /** Only health, not dirtiness: a normal write in progress is not news. */
        String health() {
            return failure + "/" + readOnly;
        }

        public String line() {
            return store + " owner " + owner + " saved=" + savedRevision + (dirty ? " dirty" : "")
                    + (failure == null ? "" : " failure=" + failure)
                    + (readOnly == null ? "" : " read-only=" + readOnly);
        }
    }

    private final Map<String, String> last = new HashMap<>();

    public static List<Entry> snapshot(Map<String, Map<UUID, StoreWriteStatus>> sources) {
        List<Entry> entries = new ArrayList<>();
        if (sources == null) {
            return entries;
        }
        for (Map.Entry<String, Map<UUID, StoreWriteStatus>> source : sources.entrySet()) {
            if (source.getKey() == null || source.getValue() == null) continue;
            for (Map.Entry<UUID, StoreWriteStatus> row : source.getValue().entrySet()) {
                if (row.getKey() == null || row.getValue() == null) continue;
                StoreWriteStatus status = row.getValue();
                entries.add(new Entry(
                        source.getKey(),
                        row.getKey(),
                        status.dirty(),
                        status.failure(),
                        status.readOnlyReason(),
                        status.savedRevision()));
            }
        }
        return entries;
    }

    /** Entries whose health changed since the previous call; a first sighting reports only unhealthy stores. */
    public List<Entry> transitions(Map<String, Map<UUID, StoreWriteStatus>> sources) {
        Map<String, String> next = new HashMap<>();
        List<Entry> changed = new ArrayList<>();
        for (Entry entry : snapshot(sources)) {
            String key = entry.store() + "/" + entry.owner();
            String health = entry.health();
            next.put(key, health);
            String previous = last.get(key);
            if (health.equals(previous)) continue;
            if (previous == null && !entry.unhealthy()) continue;
            changed.add(entry);
        }
        last.clear();
        last.putAll(next);
        return changed;
    }

    /** The same facts for one owner, as diagnostic lines. */
    public static String summary(Map<String, Map<UUID, StoreWriteStatus>> sources, UUID owner) {
        StringBuilder text = new StringBuilder();
        for (Entry entry : snapshot(sources)) {
            if (owner != null && owner.equals(entry.owner())) {
                text.append(entry.line()).append('\n');
            }
        }
        return text.toString();
    }
}
