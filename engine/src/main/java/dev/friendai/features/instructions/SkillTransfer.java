package dev.friendai.features.instructions;

import com.google.gson.*;
import java.util.*;

/** Portable editable fields only. Metadata supplied by imports is discarded. */
public final class SkillTransfer {
    private static final Gson GSON = new Gson();

    private SkillTransfer() {}

    public static SkillDraft read(String json) {
        if (json == null || json.length() > 32768) throw new IllegalArgumentException("Invalid skill input.");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new SkillDraft(
                string(o, "name"),
                string(o, "description"),
                string(o, "body"),
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                o.has("alwaysOn") && o.get("alwaysOn").getAsBoolean(),
                kinds(o),
                strings(o, "triggers"),
                strings(o, "tags"),
                o.has("includeProfile") && o.get("includeProfile").getAsBoolean());
    }

    public static String write(InstructionSkill skill) {
        return write(skill.draft());
    }

    /** Portable, flat representation of an editable draft; re-read through {@link #read(String)}. */
    public static String write(SkillDraft draft) {
        return GSON.toJson(draft);
    }

    private static String string(JsonObject o, String name) {
        return o.has(name) ? o.get(name).getAsString() : "";
    }

    private static List<String> strings(JsonObject o, String name) {
        if (!o.has(name)) return List.of();
        var a = o.getAsJsonArray(name);
        if (a.size() > 12) throw new IllegalArgumentException("Too many terms.");
        List<String> result = new ArrayList<>();
        a.forEach(e -> result.add(e.getAsString()));
        return result;
    }

    private static Set<InstructionSkill.Kind> kinds(JsonObject o) {
        if (!o.has("turnKinds")) return Set.of();
        var a = o.getAsJsonArray("turnKinds");
        if (a.size() > 6) throw new IllegalArgumentException("Too many kinds.");
        Set<InstructionSkill.Kind> result = EnumSet.noneOf(InstructionSkill.Kind.class);
        a.forEach(e -> result.add(InstructionSkill.Kind.valueOf(e.getAsString().toUpperCase(Locale.ROOT))));
        return result;
    }
}
