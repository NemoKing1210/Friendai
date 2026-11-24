package dev.friendai.features.acquisition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The effective options of one acquisition order. An omitted option means its default — an acquisition looks around
 * and through nearby containers unless the owner says otherwise — so every reader that must agree with the task (the
 * tool, the queue's restore classification) resolves it the same way.
 */
public final class ObtainOptions {
    public static final boolean USE_CONTAINERS_DEFAULT = true;
    public static final boolean EXPLORE_DEFAULT = true;

    private ObtainOptions() {}

    public static boolean useContainers(JsonObject args) {
        return flag(args, "use_containers", USE_CONTAINERS_DEFAULT);
    }

    public static boolean explore(JsonObject args) {
        return flag(args, "explore", EXPLORE_DEFAULT);
    }

    private static boolean flag(JsonObject args, String name, boolean fallback) {
        if (args == null) {
            return fallback;
        }
        JsonElement value = args.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }
}
