package dev.friendai.infrastructure.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.foundation.persistence.ProtectedConfigException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Overlays typed edits without dropping extensions or resurrecting deliberately deleted entries. */
final class ConfigJson {
    private ConfigJson() {}

    static JsonObject merge(JsonObject source, JsonObject known, JsonObject previous) {
        JsonObject result = source.deepCopy();
        for (String name : previous.keySet()) {
            if (!known.has(name)) result.remove(name);
        }
        for (var entry : known.entrySet()) {
            result.add(
                    entry.getKey(),
                    mergeValue(source.get(entry.getKey()), entry.getValue(), previous.get(entry.getKey())));
        }
        return result;
    }

    private static JsonElement mergeValue(JsonElement source, JsonElement known, JsonElement previous) {
        if (source != null && source.isJsonObject() && known.isJsonObject()) {
            return merge(
                    source.getAsJsonObject(),
                    known.getAsJsonObject(),
                    previous != null && previous.isJsonObject() ? previous.getAsJsonObject() : new JsonObject());
        }
        if (source != null
                && source.isJsonArray()
                && known.isJsonArray()
                && previous != null
                && previous.isJsonArray()) {
            return mergeArray(source.getAsJsonArray(), known.getAsJsonArray(), previous.getAsJsonArray());
        }
        return known.deepCopy();
    }

    private static JsonArray mergeArray(JsonArray source, JsonArray known, JsonArray previous) {
        // Deserializing a typed list preserves its positions, including nulls. Match unchanged values first,
        // so reordering and deletion retain each surviving element's own extension fields.
        if (source.size() != previous.size()) throw new ProtectedConfigException();
        Map<JsonElement, ArrayDeque<Integer>> positions = new HashMap<>();
        for (int i = 0; i < previous.size(); i++) {
            positions
                    .computeIfAbsent(previous.get(i), key -> new ArrayDeque<>())
                    .add(i);
        }
        int[] matches = new int[known.size()];
        java.util.Arrays.fill(matches, -1);
        boolean[] used = new boolean[previous.size()];
        List<Integer> additions = new ArrayList<>();
        for (int i = 0; i < known.size(); i++) {
            var candidates = positions.get(known.get(i));
            if (candidates == null || candidates.isEmpty()) {
                additions.add(i);
            } else {
                matches[i] = candidates.removeFirst();
                used[matches[i]] = true;
            }
        }
        List<Integer> removals = new ArrayList<>();
        boolean hasExtensions = false;
        for (int i = 0; i < previous.size(); i++) {
            if (used[i]) continue;
            removals.add(i);
            hasExtensions |=
                    !mergeValue(source.get(i), previous.get(i), previous.get(i)).equals(previous.get(i));
        }
        if (additions.size() == 1 && removals.size() == 1) {
            // One edited element has an unambiguous identity even if its known fields changed.
            matches[additions.getFirst()] = removals.getFirst();
        } else if (!additions.isEmpty() && hasExtensions) {
            // Several edits have no stable identity. Refuse to guess which private metadata belongs where.
            throw new ProtectedConfigException();
        }
        JsonArray result = new JsonArray();
        for (int i = 0; i < known.size(); i++) {
            int index = matches[i];
            result.add(
                    index < 0
                            ? known.get(i).deepCopy()
                            : mergeValue(source.get(index), known.get(i), previous.get(index)));
        }
        return result;
    }
}
