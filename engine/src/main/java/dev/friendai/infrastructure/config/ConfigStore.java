package dev.friendai.infrastructure.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.foundation.persistence.ProtectedConfigException;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Versioned JSON storage with optional, validated migrations and backup-before-replace protection. */
public final class ConfigStore<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/config");
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final Path file;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final int currentVersion;
    private final ConfigMigration migration;
    private final Consumer<T> validator;
    private final ConfigFileOperations files;
    private boolean recoveredOnLastLoad;
    private boolean unsupported;
    private boolean protectedRecovery;

    public ConfigStore(Path file, Class<T> type, Supplier<T> defaults, int currentVersion) {
        this(file, type, defaults, currentVersion, null, value -> {});
    }

    public ConfigStore(
            Path file,
            Class<T> type,
            Supplier<T> defaults,
            int currentVersion,
            ConfigMigration migration,
            Consumer<T> validator) {
        this(file, type, defaults, currentVersion, migration, validator, new ConfigFileOperations());
    }

    ConfigStore(
            Path file,
            Class<T> type,
            Supplier<T> defaults,
            int currentVersion,
            ConfigMigration migration,
            Consumer<T> validator,
            ConfigFileOperations files) {
        this.file = Objects.requireNonNull(file);
        this.type = Objects.requireNonNull(type);
        this.defaults = Objects.requireNonNull(defaults);
        this.currentVersion = currentVersion;
        this.migration = migration;
        this.validator = Objects.requireNonNull(validator);
        this.files = Objects.requireNonNull(files);
    }

    public Path file() {
        return file;
    }

    /** True iff the last load encountered corruption, rather than a failed migration. */
    public boolean recoveredOnLastLoad() {
        return recoveredOnLastLoad;
    }

    public boolean hasUnsupportedVersion() {
        return unsupported;
    }

    /** True after a failed read, recovery or migration; writes stay blocked until a successful reload. */
    public boolean hasProtectedRecovery() {
        return protectedRecovery;
    }

    public synchronized T load() {
        recoveredOnLastLoad = false;
        unsupported = false;
        protectedRecovery = false;
        if (!Files.exists(file)) {
            T value = defaults.get();
            save(value);
            return value;
        }
        byte[] original;
        try {
            original = files.read(file);
        } catch (IOException | RuntimeException failure) {
            return protect("could not be read safely");
        }
        JsonObject json;
        try {
            json = object(original);
        } catch (CharacterCodingException failure) {
            return protect("contains invalid UTF-8");
        } catch (RuntimeException failure) {
            return recover(original);
        }
        int version = version(json);
        if (version != currentVersion) {
            if (migration == null || !migration.supports(version, currentVersion)) {
                unsupported = true;
                throw new UnsupportedStoreVersionException();
            }
            return upgrade(json, version, original);
        }
        try {
            T value = decode(json);
            validator.accept(value);
            return value;
        } catch (RuntimeException failure) {
            return recover(original);
        }
    }

    /** Unsupported versions use in-memory defaults while preserving the file and blocking writes. */
    public T loadWithDefaultsOnUnsupportedVersion() {
        try {
            return load();
        } catch (UnsupportedStoreVersionException failure) {
            LOGGER.warn(
                    "Config {} uses an unsupported version; using defaults in memory and preserving the file", file);
            return defaults.get();
        }
    }

    private T upgrade(JsonObject source, int from, byte[] original) {
        try {
            JsonObject migrated = migration.apply(source, from, currentVersion);
            T value = decode(migrated);
            JsonObject previous = GSON.toJsonTree(value, type).getAsJsonObject();
            validator.accept(value);
            JsonObject known = GSON.toJsonTree(value, type).getAsJsonObject();
            byte[] replacement = encode(ConfigJson.merge(migrated, known, previous));
            Path backup = files.backup(file, original, from);
            if (!Arrays.equals(original, files.read(file)))
                throw new IOException("configuration changed during upgrade");
            files.replace(file, replacement);
            LOGGER.info(
                    "Config {} upgraded from version {} to {}; original kept as {}",
                    file,
                    from,
                    currentVersion,
                    backup);
            return value;
        } catch (IOException | RuntimeException failure) {
            // Never route a migration failure through corruption recovery: the original may be perfectly valid.
            return protect("could not be upgraded safely");
        }
    }

    private T protect(String reason) {
        protectedRecovery = true;
        // No exception text: Gson and validators may include secrets from the source document.
        LOGGER.warn("Config {} {}; keeping the original read-only and using defaults in memory", file, reason);
        return defaults.get();
    }

    private T recover(byte[] original) {
        recoveredOnLastLoad = true;
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.write(backup, original);
            T value = defaults.get();
            validator.accept(value);
            if (!Arrays.equals(original, files.read(file)))
                throw new IOException("configuration changed during recovery");
            files.replace(file, encode(GSON.toJsonTree(value, type).getAsJsonObject()));
            LOGGER.warn("Config {} is broken; using defaults, original kept as {}", file, backup);
            return value;
        } catch (IOException | RuntimeException failure) {
            return protect("could not be recovered safely");
        }
    }

    public synchronized void save(T value) {
        if (unsupported) throw new UnsupportedStoreVersionException();
        if (protectedRecovery) throw new ProtectedConfigException();
        JsonObject existing = existingBeforeSave();
        validator.accept(value);
        JsonObject previous;
        try {
            previous = GSON.toJsonTree(decode(existing), type).getAsJsonObject();
        } catch (RuntimeException failure) {
            protectedRecovery = true;
            throw new ProtectedConfigException();
        }
        JsonObject json =
                ConfigJson.merge(existing, GSON.toJsonTree(value, type).getAsJsonObject(), previous);
        try {
            files.replace(file, encode(json));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private JsonObject existingBeforeSave() {
        if (!Files.exists(file)) return new JsonObject();
        JsonObject json;
        try {
            json = object(files.read(file));
        } catch (IOException | RuntimeException failure) {
            protectedRecovery = true;
            throw new ProtectedConfigException();
        }
        if (version(json) != currentVersion) {
            unsupported = true;
            throw new UnsupportedStoreVersionException();
        }
        return json;
    }

    private T decode(JsonObject json) {
        return Objects.requireNonNull(GSON.fromJson(json, type), "empty configuration");
    }

    private int version(JsonObject json) {
        try {
            var value = json.getAsJsonPrimitive("version");
            if (value == null || !value.isNumber()) throw new ArithmeticException();
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException failure) {
            unsupported = true;
            throw new UnsupportedStoreVersionException();
        }
    }

    private static JsonObject object(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private byte[] encode(JsonObject json) {
        json.addProperty("version", currentVersion);
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ConfigFileOperations.MAX_BYTES)
            throw new IllegalStateException("configuration exceeds size limit");
        return bytes;
    }
}
