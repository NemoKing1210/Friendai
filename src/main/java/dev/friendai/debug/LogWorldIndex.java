package dev.friendai.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.friendai.foundation.diagnostics.LogJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The world-independent trail beside the client tree: one line per world load and close in
 * {@code <gameDir>/logs/friendai/index.jsonl}.
 *
 * <p>It carries pointers, never events. A deleted world takes its log tree with it, and this file is what still says
 * which world a {@code boot} belonged to. It is never read by the companion, so it cannot leak world content into
 * another world. A failure to write is swallowed like every other logging failure.
 */
public final class LogWorldIndex {
    private static final Gson GSON = new Gson();

    private LogWorldIndex() {}

    public static void append(
            Path gameRoot,
            String event,
            String world,
            Path worldPath,
            String boot,
            String modVersion,
            String minecraftVersion,
            long timeMillis) {
        try {
            Files.createDirectories(gameRoot);
            JsonObject object = new JsonObject();
            object.addProperty("schema", LogJson.SCHEMA);
            object.addProperty("kind", "world");
            object.addProperty("event", event == null ? "" : event);
            object.addProperty("time", timeMillis);
            object.addProperty("world", world == null ? "" : world);
            object.addProperty(
                    "path",
                    worldPath == null
                            ? ""
                            : worldPath.toAbsolutePath().normalize().toString());
            object.addProperty("boot", boot == null ? "" : boot);
            object.addProperty("mod", modVersion == null ? "" : modVersion);
            object.addProperty("minecraft", minecraftVersion == null ? "" : minecraftVersion);
            Files.writeString(
                    gameRoot.resolve("index.jsonl"),
                    GSON.toJson(object) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A convenience line is never worth an exception on the common path.
        }
    }
}
