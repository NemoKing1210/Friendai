package dev.friendai.features.memory.transcript;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One owner's bounded conversation transcript: both sides, keyed by a monotonic id. Pure Java; the store decides
 * when to persist. Text is normalized and capped here, so a caller cannot grow the file by accident.
 */
public final class TranscriptBook {
    private final TranscriptLimits limits;
    private final TreeMap<Long, TranscriptEntry> entries = new TreeMap<>();
    private long nextId = 1;
    private long revision;
    private boolean dirty;

    public TranscriptBook(TranscriptLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public TranscriptLimits limits() {
        return limits;
    }

    /** Appends one line, then prunes by age and count. Blank text and a missing player are ignored. */
    public synchronized TranscriptEntry add(
            UUID playerUuid, String playerName, Speaker speaker, String text, long now) {
        if (playerUuid == null || speaker == null) {
            return null;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return null;
        }
        TranscriptEntry entry = new TranscriptEntry(
                nextId++, now, playerUuid, playerName == null ? "" : playerName.strip(), speaker, truncate(normalized));
        entries.put(entry.id(), entry);
        pruneLocked(now);
        dirty = true;
        revision++;
        return entry;
    }

    /** Drops entries older than the retention window, then the oldest entries beyond the count cap. */
    public synchronized void prune(long now) {
        if (pruneLocked(now)) {
            dirty = true;
            revision++;
        }
    }

    private boolean pruneLocked(long now) {
        boolean changed = false;
        if (limits.retentionMillis() > 0) {
            long cutoff = now - limits.retentionMillis();
            while (!entries.isEmpty() && entries.firstEntry().getValue().atMillis() < cutoff) {
                entries.pollFirstEntry();
                changed = true;
            }
        }
        while (entries.size() > limits.maxEntries()) {
            entries.pollFirstEntry();
            changed = true;
        }
        return changed;
    }

    /** Entries of one player inside the window, oldest first unless {@code newestFirst}. */
    public synchronized List<TranscriptEntry> query(
            UUID playerUuid, long sinceMillis, long beforeId, int limit, boolean newestFirst) {
        if (playerUuid == null || limit < 1) {
            return List.of();
        }
        List<TranscriptEntry> matching = new ArrayList<>();
        for (TranscriptEntry entry : entries.values()) {
            if (!playerUuid.equals(entry.playerUuid()) || entry.atMillis() < sinceMillis) {
                continue;
            }
            if (beforeId > 0 && entry.id() >= beforeId) {
                continue;
            }
            matching.add(entry);
        }
        if (matching.size() > limit) {
            matching = newestFirst
                    ? new ArrayList<>(matching.subList(matching.size() - limit, matching.size()))
                    : new ArrayList<>(matching.subList(0, limit));
        }
        if (newestFirst) {
            matching.sort(Comparator.comparingLong(TranscriptEntry::id).reversed());
        }
        return List.copyOf(matching);
    }

    /** The newest {@code limit} entries across every player, newest first. */
    public synchronized List<TranscriptEntry> all(int limit) {
        List<TranscriptEntry> all = new ArrayList<>(entries.values());
        if (all.size() > Math.max(0, limit)) {
            all = new ArrayList<>(all.subList(all.size() - Math.max(0, limit), all.size()));
        }
        all.sort(Comparator.comparingLong(TranscriptEntry::id).reversed());
        return List.copyOf(all);
    }

    public synchronized List<TranscriptEntry> snapshot() {
        return List.copyOf(entries.values());
    }

    public synchronized int clearPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return 0;
        }
        int before = entries.size();
        entries.entrySet().removeIf(entry -> playerUuid.equals(entry.getValue().playerUuid()));
        int removed = before - entries.size();
        if (removed > 0) {
            dirty = true;
            revision++;
        }
        return removed;
    }

    public synchronized int clearAll() {
        int removed = entries.size();
        if (removed > 0) {
            entries.clear();
            dirty = true;
            revision++;
        }
        return removed;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void markClean() {
        dirty = false;
    }

    public synchronized long nextId() {
        return nextId;
    }

    /** Restores one entry from a file; malformed values are rejected by the caller. */
    synchronized void restore(TranscriptEntry entry) {
        entries.put(entry.id(), entry);
        nextId = Math.max(nextId, entry.id() + 1);
    }

    synchronized void restoreNextId(long value) {
        nextId = Math.max(nextId, value);
    }

    synchronized void trimToLimits() {
        while (entries.size() > limits.maxEntries()) {
            entries.pollFirstEntry();
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private String truncate(String text) {
        if (text.length() <= limits.maxTextChars()) {
            return text;
        }
        return text.substring(0, Math.max(0, limits.maxTextChars() - 1)) + "…";
    }
}
