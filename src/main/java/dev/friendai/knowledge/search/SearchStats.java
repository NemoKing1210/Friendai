package dev.friendai.knowledge.search;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Per-kind search counters, so the priors can be tuned from real runs rather than taste. */
public final class SearchStats {
    private static final int MAX_KINDS = 32;

    public record Counters(int finds, int misses, double travelBlocks, long ticks, int excavationBlocks) {
        public Counters {
            if (finds < 0 || misses < 0 || !Double.isFinite(travelBlocks) || travelBlocks < 0 || ticks < 0
                    || excavationBlocks < 0) throw new IllegalArgumentException("invalid search counters");
        }

        public Counters plus(boolean found, double travel, long tickCost, int blocks) {
            return new Counters(finds + (found ? 1 : 0), misses + (found ? 0 : 1), travelBlocks + travel,
                    ticks + tickCost, excavationBlocks + blocks);
        }

        /** Excavated blocks per find; zero when nothing has been found yet. */
        public double blocksPerFind() {
            return finds == 0 ? 0 : (double) excavationBlocks / finds;
        }
    }

    private final Map<String, Counters> counters = new TreeMap<>();

    public synchronized void record(String kind, boolean found, double travelBlocks, long ticks, int excavationBlocks) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind must not be blank");
        if (travelBlocks < 0 || ticks < 0 || excavationBlocks < 0)
            throw new IllegalArgumentException("invalid search work");
        if (!counters.containsKey(kind) && counters.size() >= MAX_KINDS) return;
        counters.merge(kind, new Counters(found ? 1 : 0, found ? 0 : 1, travelBlocks, ticks, excavationBlocks),
                (left, right) -> new Counters(left.finds() + right.finds(), left.misses() + right.misses(),
                        left.travelBlocks() + right.travelBlocks(), left.ticks() + right.ticks(),
                        left.excavationBlocks() + right.excavationBlocks()));
    }

    public synchronized Counters counters(String kind) {
        return counters.getOrDefault(kind, new Counters(0, 0, 0, 0, 0));
    }

    /** Replaces one kind's counters, used when a persisted ledger is restored. */
    public synchronized void put(String kind, Counters value) {
        if (kind == null || kind.isBlank() || value == null) return;
        if (!counters.containsKey(kind) && counters.size() >= MAX_KINDS) return;
        counters.put(kind, value);
    }

    public synchronized Set<String> kinds() { return Set.copyOf(counters.keySet()); }

    public synchronized JsonObject toJson() {
        JsonObject json = new JsonObject();
        counters.forEach((kind, value) -> {
            JsonObject row = new JsonObject();
            row.addProperty("finds", value.finds());
            row.addProperty("misses", value.misses());
            row.addProperty("travel", value.travelBlocks());
            row.addProperty("ticks", value.ticks());
            row.addProperty("excavated", value.excavationBlocks());
            json.add(kind, row);
        });
        return json;
    }

    public static SearchStats fromJson(JsonObject json) {
        SearchStats stats = new SearchStats();
        if (json == null) return stats;
        for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
            if (stats.counters.size() >= MAX_KINDS || !entry.getValue().isJsonObject()) continue;
            JsonObject row = entry.getValue().getAsJsonObject();
            try {
                stats.counters.put(entry.getKey(), new Counters(
                        row.has("finds") ? row.get("finds").getAsInt() : 0,
                        row.has("misses") ? row.get("misses").getAsInt() : 0,
                        row.has("travel") ? row.get("travel").getAsDouble() : 0,
                        row.has("ticks") ? row.get("ticks").getAsLong() : 0,
                        row.has("excavated") ? row.get("excavated").getAsInt() : 0));
            } catch (RuntimeException malformed) {
                // A broken counter row is dropped, not allowed to invalidate the whole knowledge file.
            }
        }
        return stats;
    }

    /** One line for the task summary; empty when nothing has ever been recorded. */
    public synchronized String summary() {
        if (counters.isEmpty()) return "";
        StringBuilder text = new StringBuilder("search stats: ");
        boolean first = true;
        for (Map.Entry<String, Counters> entry : counters.entrySet()) {
            Counters value = entry.getValue();
            if (!first) text.append("; ");
            first = false;
            text.append(entry.getKey()).append(' ').append(value.finds()).append(" finds/")
                    .append(value.misses()).append(" misses, ")
                    .append(String.format(Locale.ROOT, "%.1f", value.blocksPerFind())).append(" blocks/find");
        }
        return text.toString();
    }
}
