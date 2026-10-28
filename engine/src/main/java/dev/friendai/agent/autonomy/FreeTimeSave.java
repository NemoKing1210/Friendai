package dev.friendai.agent.autonomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The versioned text form of a {@link FreeTime}: {@code freetime:v1:} followed by JSON. */
public final class FreeTimeSave {
    public static final String PREFIX = "freetime:v1:";
    static final int MAX_INPUT = 32 * 1024;

    private FreeTimeSave() {}

    public static String encode(FreeTime state) {
        JsonObject root = new JsonObject();
        root.addProperty("on", state.on());
        root.addProperty("origin", state.origin().name());
        root.addProperty("wish", state.wish());
        root.addProperty("since", state.sinceMillis());
        root.addProperty("awaiting", state.awaitingChoice());
        root.addProperty("nextShift", state.nextShiftId());
        if (state.shift() != null) {
            root.add("shift", shift(state.shift()));
        }
        JsonArray history = new JsonArray();
        for (ShiftRecord r : state.history()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("goal", r.goal());
            o.addProperty("need", r.need().name());
            o.addProperty("success", r.success());
            o.addProperty("summary", r.summary());
            o.addProperty("ended", r.endedMillis());
            history.add(o);
        }
        root.add("history", history);
        root.add("lessons", strings(state.lessons()));
        JsonObject cooldowns = new JsonObject();
        state.cooldownUntil().forEach((need, until) -> cooldowns.addProperty(need.name(), until));
        root.add("cooldowns", cooldowns);
        root.add("away", strings(state.away()));
        return PREFIX + root;
    }

    /** The state, or empty when the text is missing, from another version, oversized or broken. */
    public static Optional<FreeTime> decode(String saved) {
        if (saved == null || saved.length() > MAX_INPUT || !saved.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            JsonObject root =
                    JsonParser.parseString(saved.substring(PREFIX.length())).getAsJsonObject();
            List<ShiftRecord> history = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("history")) {
                JsonObject o = e.getAsJsonObject();
                history.add(new ShiftRecord(
                        o.get("id").getAsLong(),
                        o.get("goal").getAsString(),
                        NeedKind.valueOf(o.get("need").getAsString()),
                        o.get("success").getAsBoolean(),
                        o.get("summary").getAsString(),
                        o.get("ended").getAsLong()));
            }
            Map<NeedKind, Long> cooldowns = new EnumMap<>(NeedKind.class);
            for (Map.Entry<String, JsonElement> e :
                    root.getAsJsonObject("cooldowns").entrySet()) {
                cooldowns.put(NeedKind.valueOf(e.getKey()), e.getValue().getAsLong());
            }
            return Optional.of(new FreeTime(
                    root.get("on").getAsBoolean(),
                    FreeTime.Origin.valueOf(root.get("origin").getAsString()),
                    root.get("wish").getAsString(),
                    root.get("since").getAsLong(),
                    root.has("shift") ? shift(root.getAsJsonObject("shift")) : null,
                    root.get("awaiting").getAsBoolean(),
                    root.get("nextShift").getAsLong(),
                    history,
                    strings(root.getAsJsonArray("lessons")),
                    cooldowns,
                    strings(root.getAsJsonArray("away"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static JsonObject shift(Shift s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("goal", s.goal());
        o.add("criteria", strings(s.criteria()));
        o.addProperty("why", s.why());
        o.addProperty("plan", s.planId());
        o.addProperty("started", s.startedMillis());
        o.addProperty("deadline", s.deadlineMillis());
        o.addProperty("reflex", s.reflex());
        o.addProperty("need", s.need().name());
        return o;
    }

    private static Shift shift(JsonObject o) {
        return new Shift(
                o.get("id").getAsLong(),
                o.get("goal").getAsString(),
                strings(o.getAsJsonArray("criteria")),
                o.get("why").getAsString(),
                o.get("plan").getAsLong(),
                o.get("started").getAsLong(),
                o.get("deadline").getAsLong(),
                o.get("reflex").getAsBoolean(),
                NeedKind.valueOf(o.get("need").getAsString()));
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }
}
