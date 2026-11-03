package dev.friendai.features.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned, bounded record for one owner's observed containers. A missing, corrupt, oversized or newer record
 * reads as a structural failure; an invalid container row is skipped so one bad row cannot discard the rest.
 */
public final class StorageMapSave {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_INPUT = 256 * 1024;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private StorageMapSave() {}

    public static String encode(StorageIndex index) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("generation", index.generation());
        JsonArray containers = new JsonArray();
        for (ContainerRecord record : index.records()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", record.key().dimension());
            row.addProperty("x", record.key().x());
            row.addProperty("y", record.key().y());
            row.addProperty("z", record.key().z());
            row.addProperty("kind", record.kind());
            row.addProperty("seen", record.lastSeenTick());
            row.addProperty("used", record.slotsUsed());
            row.addProperty("total", record.slotsTotal());
            JsonArray counts = new JsonArray();
            record.counts().forEach((id, count) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                entry.addProperty("count", count);
                counts.add(entry);
            });
            row.add("counts", counts);
            containers.add(row);
        }
        root.add("containers", containers);
        return GSON.toJson(root);
    }

    public static Optional<StorageIndex> decode(String json, StorageLimits limits) {
        if (json == null
                || json.isBlank()
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("formatVersion") || root.get("formatVersion").getAsInt() != FORMAT_VERSION) {
                return Optional.empty();
            }
            StorageIndex index = new StorageIndex(limits);
            long generation = 0;
            boolean hasGeneration = false;
            if (root.has("generation")) {
                generation = root.get("generation").getAsLong();
                hasGeneration = generation >= 0;
            }
            JsonArray rows = root.has("containers") ? root.getAsJsonArray("containers") : new JsonArray();
            for (JsonElement element : rows) {
                try {
                    index.observe(record(element.getAsJsonObject()));
                } catch (RuntimeException invalid) {
                    // One invalid row is skipped; the rest of the document still loads.
                }
            }
            if (hasGeneration) {
                index.setGeneration(generation);
            }
            return Optional.of(index);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static ContainerRecord record(JsonObject row) {
        StorageKey key = new StorageKey(
                row.get("dimension").getAsString(),
                row.get("x").getAsInt(),
                row.get("y").getAsInt(),
                row.get("z").getAsInt());
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray entries = row.getAsJsonArray("counts");
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            counts.put(entry.get("id").getAsString(), entry.get("count").getAsInt());
        }
        return new ContainerRecord(
                key,
                row.get("kind").getAsString(),
                row.get("seen").getAsLong(),
                row.get("used").getAsInt(),
                row.get("total").getAsInt(),
                counts);
    }
}
