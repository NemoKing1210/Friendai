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

/** JSON payload for a {@link FreeTime}; the owner record supplies its format version. */
public final class FreeTimeSave {
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
            o.addProperty("stops", r.stopWhen().name());
            history.add(o);
        }
        root.add("history", history);
        root.add("lessons", strings(state.lessons()));
        JsonObject cooldowns = new JsonObject();
        state.cooldownUntil().forEach((need, until) -> cooldowns.addProperty(need.name(), until));
        root.add("cooldowns", cooldowns);
        root.add("away", strings(state.away()));
        return root.toString();
    }

    /** The state, or empty when the text is missing, from another version, oversized or broken. */
    public static Optional<FreeTime> decode(String saved) {
        if (saved == null || saved.length() > MAX_INPUT) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(saved).getAsJsonObject();
            List<ShiftRecord> history = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("history")) {
                JsonObject o = e.getAsJsonObject();
                history.add(new ShiftRecord(
                        o.get("id").getAsLong(),
                        o.get("goal").getAsString(),
                        NeedKind.valueOf(o.get("need").getAsString()),
                        o.get("success").getAsBoolean(),
                        o.get("summary").getAsString(),
                        o.get("ended").getAsLong(),
                        o.has("stops") ? ShiftStopWhen.valueOf(o.get("stops").getAsString()) : ShiftStopWhen.DONE));
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
        o.addProperty("purpose", s.purpose());
        o.addProperty("budget", s.budget());
        o.addProperty("stops", s.stopWhen().name());
        return o;
    }

    private static Shift shift(JsonObject o) {
        NeedKind need = NeedKind.valueOf(o.get("need").getAsString());
        long started = o.get("started").getAsLong();
        long deadline = o.get("deadline").getAsLong();
        return new Shift(
                o.get("id").getAsLong(),
                o.get("goal").getAsString(),
                strings(o.getAsJsonArray("criteria")),
                o.get("why").getAsString(),
                o.get("plan").getAsLong(),
                started,
                deadline,
                o.get("reflex").getAsBoolean(),
                need,
                o.has("purpose") ? o.get("purpose").getAsString() : ShiftContract.purpose(need),
                o.has("budget") ? o.get("budget").getAsString() : ShiftContract.budget(started, deadline),
                o.has("stops") ? ShiftStopWhen.valueOf(o.get("stops").getAsString()) : ShiftStopWhen.DONE);
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
