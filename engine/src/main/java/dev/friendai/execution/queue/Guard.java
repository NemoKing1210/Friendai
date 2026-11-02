package dev.friendai.execution.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Closed, serializable tree of conditions used by queued jobs and conditional plans. */
public sealed interface Guard
        permits Guard.HasItem,
                Guard.InventorySpace,
                Guard.HealthAtLeast,
                Guard.OwnerWithin,
                Guard.BlockWithin,
                Guard.InDimension,
                Guard.AllOf,
                Guard.AnyOf,
                Guard.Not,
                Guard.BlockInFront,
                Guard.InBiome,
                Guard.FluidAhead,
                Guard.DistanceTravelled,
                Guard.TimeElapsed,
                Guard.OwnerHasAtLeast,
                Guard.BlockAt,
                Guard.OwnerOnline,
                Guard.OwnerOffline,
                Guard.Daytime,
                Guard.Nighttime,
                Guard.WeatherIs,
                Guard.FoodAtLeast,
                Guard.XpAtLeast,
                Guard.StationWithin,
                Guard.ToolAtLeast,
                Guard.PlanDone,
                Guard.GoalMet {
    int MAX_CHILDREN = 8;
    int MAX_DEPTH = 8;

    boolean holds(GuardFacts facts);

    String describe();

    JsonObject toJson();

    static Optional<Guard> parse(JsonObject json) {
        return parse(json, 1);
    }

    private static Optional<Guard> parse(JsonObject json, int depth) {
        if (json == null
                || depth > MAX_DEPTH
                || !json.has("type")
                || !json.get("type").isJsonPrimitive()
                || !json.getAsJsonPrimitive("type").isString()) {
            return Optional.empty();
        }
        try {
            String type = json.get("type").getAsString();
            return switch (type) {
                case "has_item" -> Optional.of(new HasItem(string(json, "item"), integer(json, "count", 1)));
                case "inventory_space" -> Optional.of(new InventorySpace(integer(json, "slots", 1)));
                case "health_at_least" -> Optional.of(new HealthAtLeast(integer(json, "percent", 50)));
                case "owner_within" -> Optional.of(new OwnerWithin(integer(json, "blocks", 16)));
                case "block_within" ->
                    Optional.of(new BlockWithin(
                            string(json, "block"), integer(json, "radius", 16), integer(json, "count", 1)));
                case "dimension" -> Optional.of(new InDimension(string(json, "dimension")));
                case "all_of" -> parseGroup(json, depth, true);
                case "any_of" -> parseGroup(json, depth, false);
                case "not" -> parseNot(json, depth);
                case "block_in_front" -> {
                    if (!onlyKeys(json, "type", "block", "distance")) yield Optional.empty();
                    yield Optional.of(
                            new BlockInFront(strictString(json, "block"), strictInteger(json, "distance", 1)));
                }
                case "biome" -> {
                    if (!onlyKeys(json, "type", "biome")) yield Optional.empty();
                    yield Optional.of(new InBiome(strictString(json, "biome")));
                }
                case "fluid_ahead" -> onlyKeys(json, "type") ? Optional.of(new FluidAhead()) : Optional.empty();
                case "distance_travelled" -> {
                    if (!onlyKeys(json, "type", "blocks")) yield Optional.empty();
                    yield Optional.of(new DistanceTravelled(strictInteger(json, "blocks")));
                }
                case "time_elapsed" -> {
                    if (!onlyKeys(json, "type", "ticks")) yield Optional.empty();
                    yield Optional.of(new TimeElapsed(strictLong(json, "ticks")));
                }
                case "delivered" ->
                    Optional.of(new OwnerHasAtLeast(strictString(json, "item"), strictInteger(json, "count")));
                case "block_at" ->
                    Optional.of(new BlockAt(
                            strictString(json, "block"),
                            strictInteger(json, "x"),
                            strictInteger(json, "y"),
                            strictInteger(json, "z")));
                case "owner_online" -> onlyKeys(json, "type") ? Optional.of(new OwnerOnline()) : Optional.empty();
                case "owner_offline" -> onlyKeys(json, "type") ? Optional.of(new OwnerOffline()) : Optional.empty();
                case "daytime" -> onlyKeys(json, "type") ? Optional.of(new Daytime()) : Optional.empty();
                case "nighttime" -> onlyKeys(json, "type") ? Optional.of(new Nighttime()) : Optional.empty();
                case "weather" ->
                    onlyKeys(json, "type", "weather")
                            ? Optional.of(new WeatherIs(strictString(json, "weather")))
                            : Optional.empty();
                case "food_at_least" ->
                    onlyKeys(json, "type", "points")
                            ? Optional.of(new FoodAtLeast(strictInteger(json, "points")))
                            : Optional.empty();
                case "xp_at_least" ->
                    onlyKeys(json, "type", "levels")
                            ? Optional.of(new XpAtLeast(strictInteger(json, "levels")))
                            : Optional.empty();
                case "station_within" ->
                    onlyKeys(json, "type", "station", "radius")
                            ? Optional.of(
                                    new StationWithin(strictString(json, "station"), strictInteger(json, "radius")))
                            : Optional.empty();
                case "tool_at_least" ->
                    onlyKeys(json, "type", "block")
                            ? Optional.of(new ToolAtLeast(strictString(json, "block")))
                            : Optional.empty();
                case "plan_done" ->
                    onlyKeys(json, "type", "plan")
                            ? Optional.of(new PlanDone(strictLong(json, "plan")))
                            : Optional.empty();
                case "goal_met" ->
                    onlyKeys(json, "type", "goal")
                            ? Optional.of(new GoalMet(strictLong(json, "goal")))
                            : Optional.empty();
                default -> Optional.empty();
            };
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Guard> parseGroup(JsonObject json, int depth, boolean all) {
        if (!onlyKeys(json, "type", "conditions")
                || !json.has("conditions")
                || !json.get("conditions").isJsonArray()) {
            return Optional.empty();
        }
        JsonArray rows = json.getAsJsonArray("conditions");
        if (rows.isEmpty() || rows.size() > MAX_CHILDREN) return Optional.empty();
        List<Guard> children = new ArrayList<>(rows.size());
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) return Optional.empty();
            Optional<Guard> child = parse(row.getAsJsonObject(), depth + 1);
            if (child.isEmpty()) return Optional.empty();
            children.add(child.get());
        }
        return Optional.of(all ? new AllOf(children) : new AnyOf(children));
    }

    private static Optional<Guard> parseNot(JsonObject json, int depth) {
        if (!onlyKeys(json, "type", "condition")
                || !json.has("condition")
                || !json.get("condition").isJsonObject()) {
            return Optional.empty();
        }
        return parse(json.getAsJsonObject("condition"), depth + 1).map(Not::new);
    }

    private static boolean onlyKeys(JsonObject json, String... keys) {
        if (json.size() != keys.length) return false;
        for (String key : keys) if (!json.has(key)) return false;
        return true;
    }

    private static int depth(Guard guard) {
        if (guard instanceof AllOf group)
            return 1 + group.conditions().stream().mapToInt(Guard::depth).max().orElse(0);
        if (guard instanceof AnyOf group)
            return 1 + group.conditions().stream().mapToInt(Guard::depth).max().orElse(0);
        if (guard instanceof Not not) return 1 + depth(not.condition());
        return 1;
    }

    private static List<Guard> children(List<Guard> source) {
        List<Guard> copy = source == null ? List.of() : List.copyOf(source);
        if (copy.isEmpty()
                || copy.size() > MAX_CHILDREN
                || 1 + copy.stream().mapToInt(Guard::depth).max().orElse(0) > MAX_DEPTH) {
            throw new IllegalArgumentException("condition depth or child count");
        }
        return copy;
    }

    private static int integer(JsonObject json, String name, int fallback) {
        return !json.has(name) || json.get(name).isJsonNull()
                ? fallback
                : json.get(name).getAsInt();
    }

    private static String string(JsonObject json, String name) {
        if (!json.has(name) || json.get(name).isJsonNull()) throw new IllegalArgumentException("missing " + name);
        return json.get(name).getAsString();
    }

    private static String strictString(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("invalid " + name);
        }
        String result = value.getAsString();
        if (result.isBlank()) throw new IllegalArgumentException("blank " + name);
        return result;
    }

    private static int strictInteger(JsonObject json, String name) {
        return strictInteger(json, name, Integer.MIN_VALUE);
    }

    private static int strictInteger(JsonObject json, String name, int fallback) {
        if (!json.has(name)) {
            if (fallback != Integer.MIN_VALUE) return fallback;
            throw new IllegalArgumentException("missing " + name);
        }
        JsonElement value = json.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("invalid " + name);
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return (int) number;
    }

    private static long strictLong(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("invalid " + name);
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || number < Long.MIN_VALUE || number > Long.MAX_VALUE) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return (long) number;
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
        public HasItem {
            item = id(item);
            if (count < 1) throw new IllegalArgumentException("count");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.countItems(item) >= count;
        }

        @Override
        public String describe() {
            return count + " " + item;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("has_item");
            j.addProperty("item", item);
            j.addProperty("count", count);
            return j;
        }
    }

    record InventorySpace(int slots) implements Guard {
        public InventorySpace {
            if (slots < 1) throw new IllegalArgumentException("slots");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.emptySlots() >= slots;
        }

        @Override
        public String describe() {
            return slots + " empty slots";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("inventory_space");
            j.addProperty("slots", slots);
            return j;
        }
    }

    record HealthAtLeast(int percent) implements Guard {
        public HealthAtLeast {
            if (percent < 1 || percent > 100) throw new IllegalArgumentException("percent");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.healthFraction() * 100 >= percent;
        }

        @Override
        public String describe() {
            return "health at least " + percent + "%";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("health_at_least");
            j.addProperty("percent", percent);
            return j;
        }
    }

    record OwnerWithin(int blocks) implements Guard {
        public OwnerWithin {
            if (blocks < 1 || blocks > 256) throw new IllegalArgumentException("blocks");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.ownerDistance() >= 0 && facts.ownerDistance() <= blocks;
        }

        @Override
        public String describe() {
            return "owner within " + blocks + " blocks";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("owner_within");
            j.addProperty("blocks", blocks);
            return j;
        }
    }

    record BlockWithin(String block, int radius, int count) implements Guard {
        public BlockWithin {
            block = id(block);
            if (radius < 1 || radius > 32 || count < 1 || count > 256)
                throw new IllegalArgumentException("block range");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.hasBlocks(block, radius, count);
        }

        @Override
        public String describe() {
            return count + " " + block + " within " + radius + " blocks";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("block_within");
            j.addProperty("block", block);
            j.addProperty("radius", radius);
            j.addProperty("count", count);
            return j;
        }
    }

    record InDimension(String dimension) implements Guard {
        public InDimension {
            dimension = id(dimension);
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return dimension.equals(facts.dimension());
        }

        @Override
        public String describe() {
            return "in " + dimension;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("dimension");
            j.addProperty("dimension", dimension);
            return j;
        }
    }

    record AllOf(List<Guard> conditions) implements Guard {
        public AllOf {
            conditions = children(conditions);
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return conditions.stream().allMatch(g -> g.holds(facts));
        }

        @Override
        public String describe() {
            return "all of " + conditions.stream().map(Guard::describe).toList();
        }

        @Override
        public JsonObject toJson() {
            return groupJson("all_of", conditions);
        }
    }

    record AnyOf(List<Guard> conditions) implements Guard {
        public AnyOf {
            conditions = children(conditions);
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return conditions.stream().anyMatch(g -> g.holds(facts));
        }

        @Override
        public String describe() {
            return "any of " + conditions.stream().map(Guard::describe).toList();
        }

        @Override
        public JsonObject toJson() {
            return groupJson("any_of", conditions);
        }
    }

    record Not(Guard condition) implements Guard {
        public Not {
            if (condition == null || 1 + depth(condition) > MAX_DEPTH)
                throw new IllegalArgumentException("condition depth");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return !condition.holds(facts);
        }

        @Override
        public String describe() {
            return "not (" + condition.describe() + ")";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("not");
            j.add("condition", condition.toJson());
            return j;
        }
    }

    record BlockInFront(String block, int distance) implements Guard {
        public BlockInFront {
            block = id(block);
            if (distance < 1 || distance > 8) throw new IllegalArgumentException("distance");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.blockInFront(block, distance);
        }

        @Override
        public String describe() {
            return block + " in front within " + distance + " blocks";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("block_in_front");
            j.addProperty("block", block);
            j.addProperty("distance", distance);
            return j;
        }
    }

    record InBiome(String biome) implements Guard {
        public InBiome {
            biome = id(biome);
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return biome.equals(facts.biome());
        }

        @Override
        public String describe() {
            return "in biome " + biome;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("biome");
            j.addProperty("biome", biome);
            return j;
        }
    }

    record FluidAhead() implements Guard {
        @Override
        public boolean holds(GuardFacts facts) {
            return facts.fluidAhead();
        }

        @Override
        public String describe() {
            return "fluid ahead";
        }

        @Override
        public JsonObject toJson() {
            return json("fluid_ahead");
        }
    }

    record DistanceTravelled(int blocks) implements Guard {
        public DistanceTravelled {
            if (blocks < 1 || blocks > 16384) throw new IllegalArgumentException("blocks");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.distanceTravelled() >= blocks;
        }

        @Override
        public String describe() {
            return "travelled at least " + blocks + " blocks";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("distance_travelled");
            j.addProperty("blocks", blocks);
            return j;
        }
    }

    record TimeElapsed(long ticks) implements Guard {
        public TimeElapsed {
            if (ticks < 1 || ticks > 24000) throw new IllegalArgumentException("ticks");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.timeElapsed() >= ticks;
        }

        @Override
        public String describe() {
            return "at least " + ticks + " ticks elapsed";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("time_elapsed");
            j.addProperty("ticks", ticks);
            return j;
        }
    }

    record OwnerHasAtLeast(String item, int count) implements Guard {
        public OwnerHasAtLeast {
            item = id(item);
            if (count < 1) throw new IllegalArgumentException("count");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.ownerCountItems(item) >= count;
        }

        @Override
        public String describe() {
            return "deliver " + count + " " + item + " to the owner";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("delivered");
            j.addProperty("item", item);
            j.addProperty("count", count);
            return j;
        }
    }

    record BlockAt(String block, int x, int y, int z) implements Guard {
        public BlockAt {
            block = id(block);
            coordinates(x, y, z);
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.blockAt(block, x, y, z);
        }

        @Override
        public String describe() {
            return block + " at " + x + " " + y + " " + z;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("block_at");
            j.addProperty("block", block);
            j.addProperty("x", x);
            j.addProperty("y", y);
            j.addProperty("z", z);
            return j;
        }
    }

    record OwnerOnline() implements Guard {
        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.TRUE.equals(facts.ownerOnline());
        }

        @Override
        public String describe() {
            return "owner online";
        }

        @Override
        public JsonObject toJson() {
            return json("owner_online");
        }
    }

    record OwnerOffline() implements Guard {
        @Override
        public boolean holds(GuardFacts facts) {
            // Unknown is not offline: only an explicit false satisfies the guard.
            return Boolean.FALSE.equals(facts.ownerOnline());
        }

        @Override
        public String describe() {
            return "owner offline";
        }

        @Override
        public JsonObject toJson() {
            return json("owner_offline");
        }
    }

    record Daytime() implements Guard {
        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.TRUE.equals(facts.daytime());
        }

        @Override
        public String describe() {
            return "daytime";
        }

        @Override
        public JsonObject toJson() {
            return json("daytime");
        }
    }

    record Nighttime() implements Guard {
        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.FALSE.equals(facts.daytime());
        }

        @Override
        public String describe() {
            return "nighttime";
        }

        @Override
        public JsonObject toJson() {
            return json("nighttime");
        }
    }

    record WeatherIs(String weather) implements Guard {
        /** The bounded weather vocabulary; an unknown value is a parse error, not a silent false. */
        public static final List<String> WEATHER = List.of("clear", "rain", "thunder");

        public WeatherIs {
            weather = id(weather);
            if (!WEATHER.contains(weather)) throw new IllegalArgumentException("weather");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return weather.equals(facts.weather());
        }

        @Override
        public String describe() {
            return "weather " + weather;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("weather");
            j.addProperty("weather", weather);
            return j;
        }
    }

    record FoodAtLeast(int points) implements Guard {
        public FoodAtLeast {
            if (points < 0 || points > 20) throw new IllegalArgumentException("points");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.foodLevel() >= points;
        }

        @Override
        public String describe() {
            return "food at least " + points;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("food_at_least");
            j.addProperty("points", points);
            return j;
        }
    }

    record XpAtLeast(int levels) implements Guard {
        public XpAtLeast {
            if (levels < 0 || levels > 10_000) throw new IllegalArgumentException("levels");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return facts.xpLevel() >= levels;
        }

        @Override
        public String describe() {
            return "xp at least " + levels;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("xp_at_least");
            j.addProperty("levels", levels);
            return j;
        }
    }

    record StationWithin(String station, int radius) implements Guard {
        public StationWithin {
            station = id(station);
            if (radius < 1 || radius > 32) throw new IllegalArgumentException("radius");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.TRUE.equals(facts.stationWithin(station, radius));
        }

        @Override
        public String describe() {
            return station + " within " + radius + " blocks";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("station_within");
            j.addProperty("station", station);
            j.addProperty("radius", radius);
            return j;
        }
    }

    record ToolAtLeast(String block) implements Guard {
        public ToolAtLeast {
            block = id(block);
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.TRUE.equals(facts.canHarvest(block));
        }

        @Override
        public String describe() {
            return "tool able to harvest " + block;
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("tool_at_least");
            j.addProperty("block", block);
            return j;
        }
    }

    record PlanDone(long planId) implements Guard {
        public PlanDone {
            if (planId < 1) throw new IllegalArgumentException("plan");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.TRUE.equals(facts.planDone(planId));
        }

        @Override
        public String describe() {
            return "plan #" + planId + " done";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("plan_done");
            j.addProperty("plan", planId);
            return j;
        }
    }

    record GoalMet(long goalId) implements Guard {
        public GoalMet {
            if (goalId < 1) throw new IllegalArgumentException("goal");
        }

        @Override
        public boolean holds(GuardFacts facts) {
            return Boolean.TRUE.equals(facts.goalMet(goalId));
        }

        @Override
        public String describe() {
            return "goal #" + goalId + " met";
        }

        @Override
        public JsonObject toJson() {
            JsonObject j = json("goal_met");
            j.addProperty("goal", goalId);
            return j;
        }
    }

    private static void coordinates(int x, int y, int z) {
        for (int value : new int[] {x, y, z}) {
            if (value < -30_000_000 || value > 30_000_000) throw new IllegalArgumentException("position");
        }
    }

    private static JsonObject groupJson(String type, List<Guard> conditions) {
        JsonObject j = json(type);
        JsonArray rows = new JsonArray();
        conditions.forEach(g -> rows.add(g.toJson()));
        j.add("conditions", rows);
        return j;
    }
}
