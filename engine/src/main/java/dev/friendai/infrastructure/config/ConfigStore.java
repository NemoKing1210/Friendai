package dev.friendai.infrastructure.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads and saves a versioned JSON config file: defaults on first run and backup on corruption. */
public final class ConfigStore<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/config");
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final Path file;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final int currentVersion;
    private boolean recoveredOnLastLoad;
    private boolean unsupported;

    public ConfigStore(Path file, Class<T> type, Supplier<T> defaults, int currentVersion) {
        this.file = file;
        this.type = type;
        this.defaults = defaults;
        this.currentVersion = currentVersion;
    }

    public Path file() {
        return file;
    }

    /** True iff the last {@link #load()} replaced a corrupt file with defaults. */
    public boolean recoveredOnLastLoad() {
        return recoveredOnLastLoad;
    }

    /** True after the store encountered a version it cannot read or overwrite. */
    public boolean hasUnsupportedVersion() {
        return unsupported;
    }

    public T load() {
        recoveredOnLastLoad = false;
        if (unsupported) throw new UnsupportedStoreVersionException();
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
            if (version != currentVersion) {
                unsupported = true;
                throw new UnsupportedStoreVersionException();
            }
            T value = GSON.fromJson(json, type);
            if (value == null) {
                throw new IllegalStateException("empty config");
            }
            return value;
        } catch (UnsupportedStoreVersionException e) {
            unsupported = true;
            throw e;
        } catch (RuntimeException e) {
            return recover(text, e);
        }
    }

    /** Uses in-memory defaults when the saved version is unsupported, preserving that file and blocking writes. */
    public T loadWithDefaultsOnUnsupportedVersion() {
        try {
            return load();
        } catch (UnsupportedStoreVersionException e) {
            LOGGER.warn(
                    "Config {} uses an unsupported version; using defaults in memory and preserving the file", file);
            return defaults.get();
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
        if (unsupported) throw new UnsupportedStoreVersionException();
        verifyExistingVersionBeforeSave();
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

    private void verifyExistingVersionBeforeSave() {
        if (!Files.exists(file)) return;
        JsonObject json;
        int version;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return;
            json = parsed.getAsJsonObject();
            version = json.has("version") ? json.get("version").getAsInt() : 0;
        } catch (IOException | RuntimeException ignored) {
            // load() owns corrupt-file recovery and backup creation.
            return;
        }
        if (version != currentVersion) {
            unsupported = true;
            throw new UnsupportedStoreVersionException();
        }
    }
}
