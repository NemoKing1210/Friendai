package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.Optional;

/** Closed, serializable set of conditions that make a queued job runnable. */
public sealed interface Guard permits Guard.HasItem, Guard.InventorySpace, Guard.HealthAtLeast,
        Guard.OwnerWithin, Guard.BlockWithin, Guard.InDimension {
    boolean holds(GuardFacts facts);
    String describe();
    JsonObject toJson();

    static Optional<Guard> parse(JsonObject json) {
        if (json == null || !json.has("type") || !json.get("type").isJsonPrimitive()) return Optional.empty();
        try {
            return switch (json.get("type").getAsString()) {
                case "has_item" -> Optional.of(new HasItem(string(json, "item"), integer(json, "count", 1)));
                case "inventory_space" -> Optional.of(new InventorySpace(integer(json, "slots", 1)));
                case "health_at_least" -> Optional.of(new HealthAtLeast(integer(json, "percent", 50)));
                case "owner_within" -> Optional.of(new OwnerWithin(integer(json, "blocks", 16)));
                case "block_within" -> Optional.of(new BlockWithin(string(json, "block"), integer(json, "radius", 16), integer(json, "count", 1)));
                case "dimension" -> Optional.of(new InDimension(string(json, "dimension")));
                default -> Optional.empty();
            };
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static int integer(JsonObject json, String name, int fallback) {
        return !json.has(name) || json.get(name).isJsonNull() ? fallback : json.get(name).getAsInt();
    }

    private static String string(JsonObject json, String name) {
        if (!json.has(name) || json.get(name).isJsonNull()) throw new IllegalArgumentException("missing " + name);
        return json.get(name).getAsString();
    }

    private static String id(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank id");
        return value;
    }

    private static JsonObject json(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    record HasItem(String item, int count) implements Guard {
        public HasItem { item = id(item); if (count < 1) throw new IllegalArgumentException("count"); }
        @Override public boolean holds(GuardFacts facts) { return facts.countItems(item) >= count; }
        @Override public String describe() { return count + " " + item; }
        @Override public JsonObject toJson() { JsonObject j = json("has_item"); j.addProperty("item", item); j.addProperty("count", count); return j; }
    }
    record InventorySpace(int slots) implements Guard {
        public InventorySpace { if (slots < 1) throw new IllegalArgumentException("slots"); }
        @Override public boolean holds(GuardFacts facts) { return facts.emptySlots() >= slots; }
        @Override public String describe() { return slots + " empty slots"; }
        @Override public JsonObject toJson() { JsonObject j = json("inventory_space"); j.addProperty("slots", slots); return j; }
    }
    record HealthAtLeast(int percent) implements Guard {
        public HealthAtLeast { if (percent < 1 || percent > 100) throw new IllegalArgumentException("percent"); }
        @Override public boolean holds(GuardFacts facts) { return facts.healthFraction() * 100 >= percent; }
        @Override public String describe() { return "health at least " + percent + "%"; }
        @Override public JsonObject toJson() { JsonObject j = json("health_at_least"); j.addProperty("percent", percent); return j; }
    }
    record OwnerWithin(int blocks) implements Guard {
        public OwnerWithin { if (blocks < 1 || blocks > 256) throw new IllegalArgumentException("blocks"); }
        @Override public boolean holds(GuardFacts facts) { return facts.ownerDistance() >= 0 && facts.ownerDistance() <= blocks; }
        @Override public String describe() { return "owner within " + blocks + " blocks"; }
        @Override public JsonObject toJson() { JsonObject j = json("owner_within"); j.addProperty("blocks", blocks); return j; }
    }
    record BlockWithin(String block, int radius, int count) implements Guard {
        public BlockWithin { block = id(block); if (radius < 1 || radius > 32 || count < 1 || count > 256) throw new IllegalArgumentException("block range"); }
        @Override public boolean holds(GuardFacts facts) { return facts.hasBlocks(block, radius, count); }
        @Override public String describe() { return count + " " + block + " within " + radius + " blocks"; }
        @Override public JsonObject toJson() { JsonObject j = json("block_within"); j.addProperty("block", block); j.addProperty("radius", radius); j.addProperty("count", count); return j; }
    }
    record InDimension(String dimension) implements Guard {
        public InDimension { dimension = id(dimension); }
        @Override public boolean holds(GuardFacts facts) { return dimension.equals(facts.dimension()); }
        @Override public String describe() { return "in " + dimension; }
        @Override public JsonObject toJson() { JsonObject j = json("dimension"); j.addProperty("dimension", dimension); return j; }
    }
}
