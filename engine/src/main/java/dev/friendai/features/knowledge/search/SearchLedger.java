package dev.friendai.features.knowledge.search;

import com.google.gson.JsonObject;
import dev.friendai.foundation.spatial.Pos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deduplicates equivalent search goals so retries cannot reset the per-goal work budget. */
public final class SearchLedger {
    public static final SearchSession.Limits LOCAL_DEFAULTS = new SearchSession.Limits(64, 256, 2_400, 128);
    private static final long REUSE_TICKS = 6_000;
    private static final int MAX_ASKED = 512;
    private static final int MAX_SESSIONS = 2_048;
    private final Map<UUID, Entry> sessions = new LinkedHashMap<>();
    private final SearchStats stats = new SearchStats();
    private final java.util.LinkedHashSet<String> askedGoals = new java.util.LinkedHashSet<>();
    private Runnable onChange;
    private long revision;

    public SearchLedger() {
        stats.onChange(this::changed);
    }

    /** Registers a listener that runs after every ledger change, so the owner's book is flushed. */
    public synchronized void onChange(Runnable listener) {
        onChange = listener;
    }

    public synchronized long revision() {
        return revision;
    }

    private void changed() {
        revision++;
        if (onChange != null) onChange.run();
    }

    /** Per-kind counters; persisted with the ledger so the priors survive a reload. */
    public SearchStats stats() {
        return stats;
    }

    /** Marks that the owner was asked about this goal, so the search never asks twice for it. */
    public synchronized void markAsked(UUID owner, String dimension, String goal) {
        if (owner == null) return;
        askedGoals.add(askedKey(owner, dimension, goal));
        while (askedGoals.size() > MAX_ASKED)
            askedGoals.remove(askedGoals.iterator().next());
        changed();
    }

    public synchronized boolean asked(UUID owner, String dimension, String goal) {
        return owner != null && askedGoals.contains(askedKey(owner, dimension, goal));
    }

    private static String askedKey(UUID owner, String dimension, String goal) {
        return owner + "|" + dimension + "|" + normalize(goal);
    }

    public synchronized Admission acquire(
            UUID owner, String dimension, String goal, long now, SearchSession.Limits requested) {
        return acquire(owner, dimension, goal, now, requested, new Pos(0, 0, 0));
    }

    /**
     * The session an order runs under. Only an interrupted trip that was cut short from the same origin under a
     * scope this request still fits is continued with its spent budget; every other order — a new base, a wider
     * authorized scope, a finished or deliberately stopped trip — gets its own session, so a later search never
     * inherits a terminal phase, a depleted budget or another origin.
     */
    public synchronized Admission acquire(
            UUID owner, String dimension, String goal, long now, SearchSession.Limits requested, Pos origin) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(requested);
        String normalized = normalize(goal);
        SearchSession interrupted = interrupted(owner, dimension, normalized, now, requested, origin);
        if (interrupted != null) {
            interrupted.resumeForNewRequest(now);
            changed();
            return new Admission(interrupted, true);
        }
        SearchSession created = new SearchSession(UUID.randomUUID(), dimension, normalized, origin, requested, now);
        created.onChange(this::changed);
        sessions.put(created.snapshot().id(), new Entry(owner, dimension, normalized, created));
        pruned(owner, dimension, normalized);
        changed();
        return new Admission(created, false);
    }

    /** The most recently interrupted compatible trip, or null when this order is a new one. */
    private SearchSession interrupted(
            UUID owner, String dimension, String goal, long now, SearchSession.Limits requested, Pos origin) {
        SearchSession best = null;
        for (Entry entry : sessions.values()) {
            if (!entry.owner().equals(owner)
                    || !entry.dimension().equals(dimension)
                    || !entry.goal().equals(goal)) {
                continue;
            }
            SearchSession candidate = entry.session();
            SearchSession.Snapshot snapshot = candidate.snapshot();
            if (!candidate.resumable()
                    || now < snapshot.lastTick()
                    || now - snapshot.lastTick() > REUSE_TICKS
                    || !snapshot.origin().equals(origin)
                    || !within(requested, snapshot.limits())) {
                continue;
            }
            if (best == null || snapshot.lastTick() > best.snapshot().lastTick()) best = candidate;
        }
        return best;
    }

    /** A request is a continuation only while every limit it asks for fits the scope the interrupted trip was granted. */
    private static boolean within(SearchSession.Limits requested, SearchSession.Limits granted) {
        return requested.radius() <= granted.radius()
                && requested.travelBlocks() <= granted.travelBlocks()
                && requested.ticks() <= granted.ticks()
                && requested.excavationBlocks() <= granted.excavationBlocks();
    }

    /**
     * Drops the finished and deliberately stopped rows of one goal once a new order takes over its key. A
     * resumable trip is kept, because a pending checkpoint may still name it.
     */
    private void pruned(UUID owner, String dimension, String goal) {
        java.util.List<UUID> gone = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Entry> row : sessions.entrySet()) {
            Entry entry = row.getValue();
            if (entry.owner().equals(owner)
                    && entry.dimension().equals(dimension)
                    && entry.goal().equals(goal)
                    && entry.session().terminal()
                    && !entry.session().resumable()) {
                gone.add(row.getKey());
            }
        }
        gone.forEach(sessions::remove);
        while (sessions.size() > MAX_SESSIONS) {
            sessions.remove(sessions.keySet().iterator().next());
        }
    }

    public synchronized void removeOwner(UUID owner) {
        sessions.values().removeIf(entry -> entry.owner().equals(owner));
    }

    public synchronized int size() {
        return sessions.size();
    }

    public synchronized java.util.List<SearchSession.Snapshot> snapshot() {
        return sessions.values().stream()
                .map(entry -> entry.session().snapshot())
                .toList();
    }

    public synchronized void interrupt(UUID sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        if (entry != null) entry.session().interrupt();
        changed();
    }

    /** Marks the sessions a reload interrupted, keeping the phase each one must resume from. */
    public synchronized void interruptActive() {
        sessions.values().stream()
                .map(Entry::session)
                .filter(session -> !session.terminal())
                .forEach(SearchSession::interruptForReload);
        changed();
    }

    /**
     * Resumes exactly the interrupted trip a checkpoint names, with its spent budget, limits and origin. A
     * session that is gone, finished, stopped on purpose or recorded under another goal or dimension is not
     * resumed, so a malformed checkpoint can neither borrow another search's budget nor reopen old work.
     */
    public synchronized SearchSession resume(UUID sessionId, UUID owner, String dimension, String goal, long now) {
        if (sessionId == null || owner == null || dimension == null || goal == null || goal.isBlank()) return null;
        Entry entry = sessions.get(sessionId);
        if (entry == null
                || !entry.owner().equals(owner)
                || !entry.dimension().equals(dimension)
                || !entry.goal().equals(normalize(goal))) {
            return null;
        }
        SearchSession session = entry.session();
        if (!session.resumable()) return null;
        session.resumeAfterReload(now);
        changed();
        return session;
    }

    public synchronized long logicalTick() {
        return sessions.values().stream()
                .mapToLong(entry -> entry.session().snapshot().lastTick())
                .max()
                .orElse(0);
    }

    public synchronized java.util.List<JsonObject> toJson() {
        return sessions.values().stream()
                .map(entry -> {
                    SearchSession.Snapshot s = entry.session().snapshot();
                    JsonObject row = new JsonObject();
                    row.addProperty("owner", entry.owner().toString());
                    row.addProperty("dimension", entry.dimension());
                    row.addProperty("goal", entry.goal());
                    row.addProperty("id", s.id().toString());
                    row.addProperty("x", s.origin().x());
                    row.addProperty("y", s.origin().y());
                    row.addProperty("z", s.origin().z());
                    row.addProperty("radius", s.limits().radius());
                    row.addProperty("travel", s.limits().travelBlocks());
                    row.addProperty("limit_ticks", s.limits().ticks());
                    row.addProperty("excavation", s.limits().excavationBlocks());
                    row.addProperty("phase", s.phase().name());
                    row.addProperty("started", s.startedTick());
                    row.addProperty("last", s.lastTick());
                    row.addProperty("spent_travel", s.spentTravelBlocks());
                    row.addProperty("spent_ticks", s.spentTicks());
                    row.addProperty("excavated", s.excavationBlocks());
                    row.addProperty("blocked", s.blockedLegs());
                    row.addProperty("discovered", s.discovered());
                    if (s.interruptedFrom() != null)
                        row.addProperty("interrupted_from", s.interruptedFrom().name());
                    return row;
                })
                .toList();
    }

    public synchronized SearchSession session(UUID sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry == null ? null : entry.session();
    }

    /** Stats and ask-once marks, stored beside the session rows in the knowledge file. */
    public synchronized JsonObject toStatsJson() {
        JsonObject json = new JsonObject();
        json.add("counters", stats.toJson());
        com.google.gson.JsonArray asked = new com.google.gson.JsonArray();
        askedGoals.forEach(asked::add);
        json.add("asked", asked);
        return json;
    }

    public synchronized void restoreStats(JsonObject json) {
        if (json == null) return;
        if (json.has("counters") && json.get("counters").isJsonObject()) {
            SearchStats restored = SearchStats.fromJson(json.get("counters").getAsJsonObject());
            restored.kinds().forEach(kind -> stats.put(kind, restored.counters(kind)));
        }
        if (json.has("asked") && json.get("asked").isJsonArray()) {
            for (var value : json.getAsJsonArray("asked")) {
                if (value.isJsonPrimitive() && askedGoals.size() < MAX_ASKED) askedGoals.add(value.getAsString());
            }
        }
    }

    public synchronized void restore(java.util.List<JsonObject> rows) {
        if (rows == null) return;
        if (rows.size() > 2_048) throw new IllegalArgumentException("too many search ledger entries");
        for (JsonObject row : rows) {
            if (row == null) throw new IllegalArgumentException("invalid search ledger entry");
            UUID owner = UUID.fromString(row.get("owner").getAsString());
            String dimension = row.get("dimension").getAsString(),
                    goal = normalize(row.get("goal").getAsString());
            SearchSession.Limits limits = new SearchSession.Limits(
                    row.get("radius").getAsInt(),
                    row.get("travel").getAsDouble(),
                    row.get("limit_ticks").getAsLong(),
                    row.get("excavation").getAsInt());
            SearchSession.Snapshot snapshot = new SearchSession.Snapshot(
                    UUID.fromString(row.get("id").getAsString()),
                    dimension,
                    goal,
                    new Pos(
                            row.get("x").getAsInt(),
                            row.get("y").getAsInt(),
                            row.get("z").getAsInt()),
                    limits,
                    SearchSession.Phase.valueOf(row.get("phase").getAsString()),
                    row.get("started").getAsLong(),
                    row.get("last").getAsLong(),
                    row.get("spent_travel").getAsDouble(),
                    row.get("spent_ticks").getAsLong(),
                    row.get("excavated").getAsInt(),
                    row.get("blocked").getAsInt(),
                    row.has("discovered")
                            && !row.get("discovered").isJsonNull()
                            && row.get("discovered").getAsBoolean(),
                    interruptedFrom(row));
            SearchSession session = SearchSession.restore(snapshot);
            session.onChange(this::changed);
            sessions.put(snapshot.id(), new Entry(owner, dimension, goal, session));
        }
    }

    private static SearchSession.Phase interruptedFrom(JsonObject row) {
        if (!row.has("interrupted_from") || row.get("interrupted_from").isJsonNull()) return null;
        try {
            return SearchSession.Phase.valueOf(row.get("interrupted_from").getAsString());
        } catch (IllegalArgumentException unknownPhase) {
            return null;
        }
    }

    private static String normalize(String goal) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal must not be blank");
        return goal.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** The session an order was admitted under, and whether it continued an interrupted trip with its spend. */
    public record Admission(SearchSession session, boolean resumed) {}

    private record Entry(UUID owner, String dimension, String goal, SearchSession session) {}
}
