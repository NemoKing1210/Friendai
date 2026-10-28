package dev.friendai.features.construction.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The saved-blueprint file format, independent of the compact LLM format:
 * {@code {"name":"...","size":[w,h,d],"blocks":[[x,y,z,"state"],...]}}.
 */
public final class BlueprintJson {
    private BlueprintJson() {}

    public static String toJson(Blueprint blueprint) {
        JsonObject root = new JsonObject();
        root.addProperty("name", blueprint.name());
        JsonArray size = new JsonArray();
        size.add(blueprint.width());
        size.add(blueprint.height());
        size.add(blueprint.depth());
        root.add("size", size);
        JsonArray blocks = new JsonArray();
        for (BlockSpec spec : blueprint.blocks()) {
            JsonArray entry = new JsonArray();
            entry.add(spec.x());
            entry.add(spec.y());
            entry.add(spec.z());
            entry.add(spec.state());
            blocks.add(entry);
        }
        root.add("blocks", blocks);
        return root.toString();
    }

    public static Optional<Blueprint> fromJson(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject root = element.getAsJsonObject();
            if (!root.has("name") || !root.has("size") || !root.has("blocks")) {
                return Optional.empty();
            }
            JsonArray size = root.getAsJsonArray("size");
            if (size.size() != 3) {
                return Optional.empty();
            }
            JsonArray blocks = root.getAsJsonArray("blocks");
            List<BlockSpec> specs = new ArrayList<>(blocks.size());
            for (JsonElement block : blocks) {
                JsonArray entry = block.getAsJsonArray();
                if (entry.size() != 4) {
                    return Optional.empty();
                }
                specs.add(new BlockSpec(
                        entry.get(0).getAsInt(),
                        entry.get(1).getAsInt(),
                        entry.get(2).getAsInt(),
                        entry.get(3).getAsString()));
            }
            return Optional.of(new Blueprint(
                    root.get("name").getAsString(),
                    size.get(0).getAsInt(),
                    size.get(1).getAsInt(),
                    size.get(2).getAsInt(),
                    specs));
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }
}
