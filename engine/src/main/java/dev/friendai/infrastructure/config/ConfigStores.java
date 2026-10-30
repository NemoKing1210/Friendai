package dev.friendai.infrastructure.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Production config schemas and their reviewed upgrade paths. Other ConfigStore users remain version-strict. */
public final class ConfigStores {
    // Additive transitions are explicit: field initializers supply missing values after the complete chain runs.
    private static final Consumer<JsonObject> ADDITIVE = json -> {};
    private static final ConfigMigration SERVER = new ConfigMigration(
            3,
            List.of(
                    ADDITIVE, // 3 -> 4: pathing
                    ADDITIVE, // 4 -> 5: chunk loading
                    ADDITIVE, // 5 -> 6: tree felling
                    ADDITIVE, // 6 -> 7: queue limits
                    ADDITIVE, // 7 -> 8: structures and resource expeditions; retain explicit permission choices
                    ADDITIVE, // 8 -> 9: agent
                    ADDITIVE, // 9 -> 10: observation radius; retain explicit radius choices
                    ADDITIVE, // 10 -> 11: places and trading
                    ADDITIVE, // 11 -> 12: autonomy
                    ConfigStores::renamePersona, // 12 -> 13: description and instruction skills
                    ADDITIVE, // 13 -> 14: search escort
                    ADDITIVE, // 14 -> 15: planner attempts
                    ADDITIVE)); // 15 -> 16: free-time watchdog
    private static final ConfigMigration CLIENT = new ConfigMigration(
            1,
            List.of(
                    ADDITIVE, // 1 -> 2: optional synthesis, disabled by default
                    ConfigStores::unifyVoice, // 2 -> 3: both historical speech shapes
                    ADDITIVE)); // 3 -> 4: movement overlay, disabled by default

    private ConfigStores() {}

    public static ConfigStore<ServerConfig> server(Path file) {
        return new ConfigStore<>(
                file, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, SERVER, ServerConfig::validate);
    }

    public static ConfigStore<ClientConfig> client(Path file) {
        return new ConfigStore<>(
                file,
                ClientConfig.class,
                ClientConfig::defaults,
                ClientConfig.CURRENT_VERSION,
                CLIENT,
                ClientConfig::validate);
    }

    private static void renamePersona(JsonObject json) {
        JsonObject companion = object(json, "companion");
        move(companion, "persona", companion, "description");
    }

    private static void unifyVoice(JsonObject json) {
        JsonObject voice = object(json, "voice");
        JsonObject stt = object(voice, "stt");
        for (String field : List.of("enabled", "language", "inputDevice", "maxRecordSec")) {
            move(voice, field, stt, field);
        }
        move(voice, "whisperModel", object(stt, "local"), "model");
        JsonObject tts = object(voice, "tts");
        JsonObject cloud = object(tts, "cloud");
        for (String provider : List.of("openai", "elevenlabs", "yandex")) {
            move(tts, provider, cloud, provider);
        }
        JsonObject local = object(tts, "local");
        JsonElement voiceId = local.get("voice");
        if (voiceId != null
                && voiceId.isJsonPrimitive()
                && voiceId.getAsJsonPrimitive().isString()) {
            String id = voiceId.getAsString();
            if (id.startsWith("vits-piper-")) local.addProperty("voice", id.substring("vits-piper-".length()));
        }
    }

    /** A new name wins conflicts; the replaced name is removed so later saves cannot revive it. */
    private static void move(JsonObject from, String oldName, JsonObject to, String newName) {
        JsonElement value = from.remove(oldName);
        if (value != null && !to.has(newName)) to.add(newName, value);
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || value.isJsonNull()) {
            JsonObject result = new JsonObject();
            parent.add(name, result);
            return result;
        }
        // Unexpected field types reject the upgrade rather than silently dropping the owner's settings.
        return value.getAsJsonObject();
    }
}
