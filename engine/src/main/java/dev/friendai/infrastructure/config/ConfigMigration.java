package dev.friendai.infrastructure.config;

import com.google.gson.JsonObject;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.util.List;
import java.util.function.Consumer;

/** An immutable, contiguous sequence of explicit schema transitions, each advancing exactly one version. */
public final class ConfigMigration {
    private final int firstVersion;
    private final List<Consumer<JsonObject>> steps;

    public ConfigMigration(int firstVersion, List<Consumer<JsonObject>> steps) {
        if (firstVersion < 1 || steps.isEmpty()) throw new IllegalArgumentException("empty migration range");
        this.firstVersion = firstVersion;
        this.steps = List.copyOf(steps);
    }

    public boolean supports(int from, int target) {
        return from >= firstVersion && from < target && target == firstVersion + steps.size();
    }

    /** Transforms a private copy; a missing transition rejects the entire upgrade before any step runs. */
    public JsonObject apply(JsonObject source, int from, int target) {
        if (!supports(from, target)) throw new UnsupportedStoreVersionException();
        JsonObject result = source.deepCopy();
        for (int version = from; version < target; version++) {
            steps.get(version - firstVersion).accept(result);
            result.addProperty("version", version + 1);
        }
        return result;
    }
}
