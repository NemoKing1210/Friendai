package dev.friendai.knowledge.search;

import com.google.gson.JsonObject;
import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded record of actual viewpoints inspected by a resource search. */
public final class SearchCoverage {
    public enum Outcome { INSPECTED, BLOCKED, EXHAUSTED }
    public record Visit(String dimension, Pos viewpoint, String family, ResourceSearchProfile.Strategy strategy,
                        Outcome outcome, long tick) {}
    private static final int MAX_VISITS = 4_096;
    private static final int MAX_PER_SECTOR = 8;
    private final List<Visit> visits = new ArrayList<>();

    public void record(String dimension, Pos viewpoint, String family, ResourceSearchProfile.Strategy strategy,
            Outcome outcome, long tick) {
        Objects.requireNonNull(viewpoint, "viewpoint");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(outcome, "outcome");
        if (dimension == null || dimension.isBlank() || family == null || family.isBlank() || tick < 0)
            throw new IllegalArgumentException("invalid coverage record");
        String sector = sector(dimension, viewpoint, family, strategy);
        if (visits.stream().filter(v -> sector(dimension, v.viewpoint(), family, strategy).equals(sector)).count()
                >= MAX_PER_SECTOR) return;
        if (visits.size() == MAX_VISITS) visits.remove(0);
        visits.add(new Visit(dimension, viewpoint, family, strategy, outcome, tick));
    }

    public boolean inspected(String dimension, Pos viewpoint, String family, ResourceSearchProfile.Strategy strategy) {
        return visits.stream().anyMatch(v -> sector(v.dimension(), v.viewpoint(), v.family(), v.strategy())
                .equals(sector(dimension, viewpoint, family, strategy)));
    }

    public boolean covered(String dimension, Pos viewpoint, String family, ResourceSearchProfile.Strategy strategy,
            long now, long blockedCooldown) {
        Visit last = visits.stream().filter(v -> sector(v.dimension(), v.viewpoint(), v.family(), v.strategy())
                .equals(sector(dimension, viewpoint, family, strategy)))
                .max(java.util.Comparator.comparingLong(Visit::tick)).orElse(null);
        if (last == null) return false;
        if (last.outcome() != Outcome.BLOCKED) return true;
        return now < last.tick() || now - last.tick() < blockedCooldown;
    }

    public long recentVisits(String dimension, Pos viewpoint, String family, ResourceSearchProfile.Strategy strategy,
            long now, long window) {
        String target = sector(dimension, viewpoint, family, strategy);
        return visits.stream().filter(v -> sector(v.dimension(), v.viewpoint(), v.family(), v.strategy()).equals(target))
                .filter(v -> now >= v.tick() && now - v.tick() <= window).count();
    }

    public long failedAttempts(String dimension, Pos viewpoint, String family, ResourceSearchProfile.Strategy strategy) {
        String target = sector(dimension, viewpoint, family, strategy);
        return visits.stream().filter(v -> sector(v.dimension(), v.viewpoint(), v.family(), v.strategy()).equals(target))
                .filter(v -> v.outcome() != Outcome.INSPECTED).count();
    }

    public List<Visit> snapshot() { return List.copyOf(visits); }

    public List<JsonObject> toJson() {
        return visits.stream().map(v -> {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", v.dimension()); row.addProperty("x", v.viewpoint().x());
            row.addProperty("y", v.viewpoint().y()); row.addProperty("z", v.viewpoint().z());
            row.addProperty("family", v.family()); row.addProperty("strategy", v.strategy().name());
            row.addProperty("outcome", v.outcome().name()); row.addProperty("tick", v.tick());
            return row;
        }).toList();
    }

    public static SearchCoverage fromJson(List<JsonObject> rows) {
        SearchCoverage result = new SearchCoverage();
        if (rows == null) return result;
        if (rows.size() > MAX_VISITS) throw new IllegalArgumentException("too many coverage records");
        for (JsonObject row : rows) {
            if (row == null || !row.has("dimension") || !row.has("x") || !row.has("y") || !row.has("z")
                    || !row.has("family") || !row.has("strategy") || !row.has("outcome") || !row.has("tick"))
                throw new IllegalArgumentException("invalid coverage record");
            result.record(row.get("dimension").getAsString(), new Pos(row.get("x").getAsInt(),
                    row.get("y").getAsInt(), row.get("z").getAsInt()), row.get("family").getAsString(),
                    ResourceSearchProfile.Strategy.valueOf(row.get("strategy").getAsString()),
                    Outcome.valueOf(row.get("outcome").getAsString()), row.get("tick").getAsLong());
        }
        if (result.visits.size() != rows.size()) throw new IllegalArgumentException("coverage contains duplicate or capped sectors");
        return result;
    }

    private static String sector(String dimension, Pos p, String family, ResourceSearchProfile.Strategy strategy) {
        return dimension + '|' + (p.x() >> 4) + '|' + (p.y() >> 4) + '|' + (p.z() >> 4) + '|' + family + '|' + strategy;
    }
}
