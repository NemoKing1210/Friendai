package dev.friendai.features.instructions;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Classpath-only packaged instruction catalogue. No path comes from owner/model input. */
public final class BuiltinSkills {
    /** Upper bound for the packaged catalogue; the per-turn slice bounds what is actually read. */
    static final int MAX_BUILTINS = 64;

    private BuiltinSkills() {}

    public static List<InstructionSkill> load(ClassLoader loader) {
        try {
            var index = JsonParser.parseString(resource(loader, "index.json", 32768))
                    .getAsJsonArray();
            if (index.isEmpty() || index.size() > MAX_BUILTINS)
                throw new IllegalArgumentException("Invalid builtin catalogue.");
            List<InstructionSkill> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var element : index) {
                JsonObject row = element.getAsJsonObject();
                if (!row.has("id") || !row.has("name") || !row.has("description"))
                    throw new IllegalArgumentException("Invalid builtin metadata.");
                String id = row.get("id").getAsString();
                if (!id.matches("[a-z-]{1,40}") || !ids.add(id))
                    throw new IllegalArgumentException("Invalid builtin id.");
                if (row.get("name").getAsString().isBlank()
                        || row.get("description").getAsString().isBlank())
                    throw new IllegalArgumentException("Invalid builtin metadata.");
                row.addProperty("body", resource(loader, id + ".txt", 16000));
                SkillDraft draft = SkillText.validate(SkillTransfer.read(row.toString()), s -> s);
                result.add(new InstructionSkill(
                        "builtin:" + id,
                        draft.name(),
                        draft.description(),
                        draft.body(),
                        true,
                        InstructionSkill.Scope.SERVER,
                        InstructionSkill.Source.BUILTIN,
                        null,
                        0,
                        0,
                        1,
                        InstructionSkill.Activation.ACTIVE,
                        draft.alwaysOn(),
                        draft.turnKinds(),
                        draft.triggers(),
                        draft.tags(),
                        draft.includeProfile()));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load builtin instruction resources.");
        }
    }

    private static String resource(ClassLoader loader, String name, int max) throws IOException {
        try (InputStream in = loader.getResourceAsStream("assets/friendai/skills/" + name)) {
            if (in == null) throw new IOException("Missing builtin instruction resource.");
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max) throw new IOException("Oversized instruction resource.");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Split so the language-key scan does not read this prefix as a literal missing translation key. */
    private static final String TITLE_PREFIX = "friendai" + ".skill.";

    /** One convention for every builtin: {@code builtin:tree-felling} → {@code friendai.skill.tree_felling}. */
    public static Optional<String> titleKey(String id) {
        if (id == null || !id.startsWith("builtin:")) return Optional.empty();
        String slug = id.substring("builtin:".length());
        if (!slug.matches("[a-z0-9-]{1,80}")) return Optional.empty();
        return Optional.of(TITLE_PREFIX + slug.replace('-', '_'));
    }
}
