package dev.friendai.knowledge.search;

import com.google.gson.JsonObject;
import dev.friendai.pathing.Pos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deduplicates equivalent search goals so retries cannot reset the per-goal work budget. */
public final class SearchLedger {
    public static final SearchSession.Limits LOCAL_DEFAULTS = new SearchSession.Limits(64, 256, 2_400, 128);
    private static final long REUSE_TICKS = 6_000;
    private static final int MAX_ASKED = 512;
    private final Map<Key, SearchSession> sessions = new LinkedHashMap<>();
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

    public synchronized SearchSession acquire(
            UUID owner, String dimension, String goal, long now, SearchSession.Limits requested) {
        return acquire(owner, dimension, goal, now, requested, new Pos(0, 0, 0));
    }

    public synchronized SearchSession acquire(
            UUID owner, String dimension, String goal, long now, SearchSession.Limits requested, Pos origin) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(requested);
        String normalized = normalize(goal);
        Key key = new Key(owner, dimension, normalized);
        SearchSession prior = sessions.get(key);
        if (prior != null
                && now >= prior.snapshot().lastTick()
                && now - prior.snapshot().lastTick() <= REUSE_TICKS) {
            prior.resumeForNewRequest(now);
            return prior;
        }
        SearchSession created = new SearchSession(UUID.randomUUID(), dimension, normalized, origin, requested, now);
        created.onChange(this::changed);
        sessions.put(key, created);
        changed();
        while (sessions.size() > 2_048)
            sessions.remove(sessions.keySet().iterator().next());
        return created;
    }

    public synchronized void removeOwner(UUID owner) {
        sessions.entrySet().removeIf(entry -> entry.getKey().owner().equals(owner));
    }

    public synchronized int size() {
        return sessions.size();
    }

    public synchronized java.util.List<SearchSession.Snapshot> snapshot() {
        return sessions.values().stream().map(SearchSession::snapshot).toList();
    }

    public synchronized void interrupt(UUID sessionId) {
        sessions.values().stream()
                .filter(session -> session.snapshot().id().equals(sessionId))
                .forEach(SearchSession::interrupt);
        changed();
    }

    public synchronized void interruptActive() {
        sessions.values().stream().filter(session -> !session.terminal()).forEach(SearchSession::interrupt);
        changed();
    }

    public synchronized long logicalTick() {
        return sessions.values().stream()
                .mapToLong(session -> session.snapshot().lastTick())
                .max()
                .orElse(0);
    }

    public synchronized java.util.List<JsonObject> toJson() {
        return sessions.entrySet().stream()
                .map(entry -> {
                    SearchSession.Snapshot s = entry.getValue().snapshot();
                    JsonObject row = new JsonObject();
                    row.addProperty("owner", entry.getKey().owner().toString());
                    row.addProperty("dimension", entry.getKey().dimension());
                    row.addProperty("goal", entry.getKey().goal());
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
                    return row;
                })
                .toList();
    }

    public synchronized SearchSession session(UUID sessionId) {
        if (sessionId == null) return null;
        return sessions.values().stream()
                .filter(session -> session.snapshot().id().equals(sessionId))
                .findFirst()
                .orElse(null);
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
                    row.get("blocked").getAsInt());
            SearchSession session = SearchSession.restore(snapshot);
            session.onChange(this::changed);
            sessions.put(new Key(owner, dimension, goal), session);
        }
    }

    private static String normalize(String goal) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal must not be blank");
        return goal.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record Key(UUID owner, String dimension, String goal) {}
}
