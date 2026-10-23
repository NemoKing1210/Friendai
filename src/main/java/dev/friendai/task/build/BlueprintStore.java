package dev.friendai.task.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BlueprintJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Saved blueprints: {@code <world>/friendai/blueprints/<name>.json}. Writes go to a temporary file first and are
 * moved into place, so a crash never leaves half a blueprint behind.
 */
public final class BlueprintStore {
    /** A saved blueprint without its blocks, for listings. */
    public record Entry(String name, int width, int height, int depth, Optional<UUID> author, long created) {}

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final String BAD_NAME = "blueprint names use a-z, 0-9, _ and -, at most 32 characters";

    private final Path dir;

    public BlueprintStore(Path dir) {
        this.dir = dir;
    }

    /** The blueprint directory of the world the level belongs to. */
    public static BlueprintStore forLevel(ServerLevel level) {
        return new BlueprintStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("blueprints"));
    }

    public static boolean validName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /** Saves the blueprint, refusing to replace one that exists unless {@code overwrite} is set. */
    public void save(String name, Blueprint blueprint, UUID author, boolean overwrite) {
        if (!validName(name)) {
            throw new IllegalArgumentException(BAD_NAME);
        }
        Path file = dir.resolve(name + ".json");
        if (Files.exists(file) && !overwrite) {
            throw new IllegalArgumentException("a blueprint named " + name + " exists; use overwrite");
        }
        JsonObject root = JsonParser.parseString(BlueprintJson.toJson(blueprint)).getAsJsonObject();
        if (author != null) {
            root.addProperty("author", author.toString());
        }
        root.addProperty("created", System.currentTimeMillis());
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, name, ".tmp");
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not save blueprint " + name, e);
        }
    }

    /** The blueprint with this name, or empty when there is none or the file is broken. */
    public Optional<Blueprint> load(String name) {
        if (!validName(name)) {
            return Optional.empty();
        }
        Path file = dir.resolve(name + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return BlueprintJson.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Every saved blueprint, sorted by name; broken files are skipped. */
    public List<Entry> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                String name = path.getFileName().toString();
                name = name.substring(0, name.length() - ".json".length());
                try {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    Optional<Blueprint> blueprint = BlueprintJson.fromJson(text);
                    if (blueprint.isEmpty()) {
                        return;
                    }
                    JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                    Optional<UUID> author = Optional.empty();
                    if (root.has("author")) {
                        try {
                            author = Optional.of(UUID.fromString(root.get("author").getAsString()));
                        } catch (IllegalArgumentException ignored) {
                            author = Optional.empty();
                        }
                    }
                    long created = root.has("created") ? root.get("created").getAsLong() : 0L;
                    Blueprint bp = blueprint.get();
                    entries.add(new Entry(name, bp.width(), bp.height(), bp.depth(), author, created));
                } catch (IOException | RuntimeException ignored) {
                    // A broken file is simply not listed; load() reports it as missing too.
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }
}
