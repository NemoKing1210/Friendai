package dev.friendai.agent.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.queue.Guard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The versioned text form of a {@link GoalBoard}: {@code goals:v1:} followed by JSON. */
public final class GoalSave {
    public static final String PREFIX = "goals:v1:";
    static final int MAX_INPUT = 32 * 1024;

    private GoalSave() {}

    public static String encode(GoalBoard board) {
        JsonObject root = new JsonObject();
        root.addProperty("nextId", board.nextId());
        JsonArray array = new JsonArray();
        for (Goal goal : board.all()) {
            JsonObject g = new JsonObject();
            g.addProperty("id", goal.id());
            g.addProperty("text", goal.text());
            g.addProperty("status", goal.status().name());
            g.addProperty("plan", goal.planId());
            g.addProperty("replans", goal.replans());
            g.addProperty("created", goal.createdTick());
            g.addProperty("background", goal.background());
            JsonArray lessons = new JsonArray();
            goal.lessons().forEach(lessons::add);
            g.add("lessons", lessons);
            JsonArray criteria = new JsonArray();
            for (Criterion c : goal.criteria()) {
                JsonObject o = new JsonObject();
                o.addProperty("text", c.text());
                c.check().ifPresent(guard -> o.add("check", guard.toJson()));
                criteria.add(o);
            }
            g.add("criteria", criteria);
            array.add(g);
        }
        root.add("goals", array);
        return PREFIX + root;
    }

    /** The board, or empty when the text is missing, from another version, oversized or broken. */
    public static Optional<GoalBoard> decode(String saved, int maxBackground, int maxReplans) {
        if (saved == null || saved.length() > MAX_INPUT || !saved.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(saved.substring(PREFIX.length())).getAsJsonObject();
            List<Goal> goals = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("goals")) {
                JsonObject g = element.getAsJsonObject();
                List<String> lessons = new ArrayList<>();
                for (JsonElement l : g.getAsJsonArray("lessons")) {
                    lessons.add(l.getAsString());
                }
                List<Criterion> criteria = new ArrayList<>();
                for (JsonElement c : g.getAsJsonArray("criteria")) {
                    JsonObject o = c.getAsJsonObject();
                    Optional<Guard> check = o.has("check") && o.get("check").isJsonObject()
                            ? Guard.parse(o.getAsJsonObject("check"))
                            : Optional.empty();
                    criteria.add(new Criterion(o.get("text").getAsString(), check));
                }
                goals.add(new Goal(
                        g.get("id").getAsLong(),
                        g.get("text").getAsString(),
                        criteria,
                        GoalStatus.valueOf(g.get("status").getAsString()),
                        g.get("plan").getAsLong(),
                        g.get("replans").getAsInt(),
                        lessons,
                        g.get("created").getAsLong(),
                        g.get("background").getAsBoolean()));
            }
            long nextId = root.has("nextId") ? root.get("nextId").getAsLong() : 1;
            return Optional.of(GoalBoard.restore(maxBackground, maxReplans, nextId, goals));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
