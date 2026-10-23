package dev.friedai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads and saves a JSON config file: defaults on first run, backup on corruption, versioned migrations. */
public final class ConfigStore<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger("friedai/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final Path file;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final int currentVersion;
    private final BiFunction<JsonObject, Integer, JsonObject> migrator;
    private boolean recoveredOnLastLoad;

    public ConfigStore(
            Path file,
            Class<T> type,
            Supplier<T> defaults,
            int currentVersion,
            BiFunction<JsonObject, Integer, JsonObject> migrator) {
        this.file = file;
        this.type = type;
        this.defaults = defaults;
        this.currentVersion = currentVersion;
        this.migrator = migrator;
    }

    public Path file() {
        return file;
    }

    /** True iff the last {@link #load()} replaced a corrupt file with defaults. */
    public boolean recoveredOnLastLoad() {
        return recoveredOnLastLoad;
    }

    public T load() {
        recoveredOnLastLoad = false;
        if (!Files.exists(file)) {
            T value = defaults.get();
            save(value);
            return value;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("config root is not an object");
            }
            JsonObject json = parsed.getAsJsonObject();
            int version = json.has("version") ? json.get("version").getAsInt() : 0;
            boolean migrated = version < currentVersion;
            if (migrated) {
                json = migrator.apply(json, version);
                json.addProperty("version", currentVersion);
            }
            T value = GSON.fromJson(json, type);
            if (value == null) {
                throw new IllegalStateException("empty config");
            }
            if (migrated) {
                save(value);
            }
            return value;
        } catch (RuntimeException e) {
            return recover(text, e);
        }
    }

    private T recover(String text, RuntimeException cause) {
        recoveredOnLastLoad = true;
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.writeString(backup, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not back up broken config {}", file, e);
        }
        LOGGER.warn("Config {} is broken ({}); using defaults, old file kept as {}", file, cause.getMessage(), backup);
        T value = defaults.get();
        save(value);
        return value;
    }

    public void save(T value) {
        JsonObject json = GSON.toJsonTree(value, type).getAsJsonObject();
        json.addProperty("version", currentVersion);
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
