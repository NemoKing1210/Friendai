package dev.friendai.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.friendai.foundation.diagnostics.LogJson;
import dev.friendai.foundation.diagnostics.LogSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The world-independent trail beside the client tree: one line per world load and close in
 * {@code <gameDir>/logs/friendai/index.jsonl}.
 *
 * <p>It carries pointers, never events. A deleted world takes its log tree with it, and this file is what still says
 * which world a {@code boot} belonged to. It is never read by the companion, so it cannot leak world content into
 * another world. A failure to write is swallowed like every other logging failure, and {@code off} writes nothing at
 * all: the index obeys the same level as the records it points at.
 */
public final class LogWorldIndex {
    /** The index is a trail, not a log: it keeps its last lines once it outgrows this. */
    static final int MAX_BYTES = 256 * 1024;

    static final int KEEP_LINES = 500;
    private static final Gson GSON = new Gson();

    private LogWorldIndex() {}

    /** Returns false when logging is off, which writes nothing anywhere. */
    public static boolean append(
            Path gameRoot,
            LogSettings settings,
            String event,
            String world,
            Path worldPath,
            String boot,
            String modVersion,
            String minecraftVersion,
            long timeMillis) {
        if (settings == null || settings.level() == null) return false;
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
            Path index = gameRoot.resolve("index.jsonl");
            Files.writeString(
                    index,
                    GSON.toJson(object) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            if (Files.size(index) > MAX_BYTES) trim(index, KEEP_LINES);
            return true;
        } catch (IOException | RuntimeException ignored) {
            // A convenience line is never worth an exception on the common path.
            return false;
        }
    }

    /** Keeps the newest {@code keepLines} lines, so the trail stays bounded without a second retention policy. */
    static void trim(Path index, int keepLines) throws IOException {
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        if (lines.size() <= keepLines) return;
        List<String> kept = lines.subList(lines.size() - keepLines, lines.size());
        Files.writeString(
                index,
                String.join("\n", kept) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }
}
