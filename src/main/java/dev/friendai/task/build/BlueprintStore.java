package dev.friendai.task.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Saved blueprints: {@code <world>/friendai/blueprints/<name>.json}. Writes go to a temporary file first and are
 * moved into place, so a crash never leaves half a blueprint behind.
 *
 * <p>Builds in progress keep their blueprint and scaffold in {@code .inprogress/<id>.json}, a hidden directory next to
 * the saved ones, so a reload can resume them. The file is deleted when the build ends.
 */
public final class BlueprintStore {
    /** A saved blueprint without its blocks, for listings. */
    public record Entry(String name, int width, int height, int depth, Optional<UUID> author, long created) {}

    /** Everything needed to resume a build: its blueprint, placement and the scaffold blocks already placed. */
    public record InProgress(
            Blueprint blueprint,
            BlockPos origin,
            Rotation rotation,
            boolean clearSite,
            boolean gather,
            List<BlockPos> scaffold) {
        public InProgress {
            scaffold = List.copyOf(scaffold);
        }
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern BUILD_ID_PATTERN = Pattern.compile("[0-9a-f]{32}");
    private static final String BAD_NAME = "blueprint names use a-z, 0-9, _ and -, at most 32 characters";
    private static final String IN_PROGRESS = ".inprogress";

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

    /** Whether {@code id} is a build id this store can map to a file. */
    public static boolean validBuildId(String id) {
        return id != null && BUILD_ID_PATTERN.matcher(id).matches();
    }

    /** The hidden directory holding the builds in progress. */
    public Path inProgressDir() {
        return dir.resolve(IN_PROGRESS);
    }

    /** The in-progress file of one build. */
    public Path inProgressFile(String id) {
        return inProgressDir().resolve(id + ".json");
    }

    /**
     * Writes the whole state of a running build: the blueprint, where it stands, and the scaffold blocks already
     * placed. Atomic, like {@link #save}. The file is replaced on every call.
     */
    public void saveInProgress(
            String id,
            Blueprint blueprint,
            BlockPos origin,
            Rotation rotation,
            boolean clearSite,
            boolean gather,
            List<BlockPos> scaffold) {
        if (!validBuildId(id)) {
            throw new IllegalArgumentException("bad build id");
        }
        JsonObject root = new JsonObject();
        root.add("blueprint", JsonParser.parseString(BlueprintJson.toJson(blueprint)).getAsJsonObject());
        root.add("origin", numbers(origin.getX(), origin.getY(), origin.getZ()));
        root.addProperty("rotation", rotation.name());
        root.addProperty("clearSite", clearSite);
        root.addProperty("gather", gather);
        JsonArray placed = new JsonArray();
        for (BlockPos pos : scaffold) {
            placed.add(numbers(pos.getX(), pos.getY(), pos.getZ()));
        }
        root.add("scaffold", placed);
        Path file = inProgressFile(id);
        try {
            Files.createDirectories(file.getParent());
            writeAtomically(file, id, root.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("could not save the build in progress " + id, e);
        }
    }

    /** The state of a running build, or empty when the file is missing or broken. Never throws. */
    public Optional<InProgress> loadInProgress(String id) {
        if (!validBuildId(id)) {
            return Optional.empty();
        }
        Path file = inProgressFile(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Optional<Blueprint> blueprint = BlueprintJson.fromJson(root.get("blueprint").toString());
            if (blueprint.isEmpty()) {
                return Optional.empty();
            }
            JsonArray origin = root.getAsJsonArray("origin");
            if (origin.size() != 3) {
                return Optional.empty();
            }
            Rotation rotation = Rotation.valueOf(root.get("rotation").getAsString());
            boolean clearSite = root.get("clearSite").getAsBoolean();
            boolean gather = root.get("gather").getAsBoolean();
            List<BlockPos> scaffold = new ArrayList<>();
            JsonArray placed = root.getAsJsonArray("scaffold");
            for (JsonElement element : placed) {
                JsonArray entry = element.getAsJsonArray();
                if (entry.size() != 3) {
                    return Optional.empty();
                }
                scaffold.add(new BlockPos(
                        entry.get(0).getAsInt(), entry.get(1).getAsInt(), entry.get(2).getAsInt()));
            }
            return Optional.of(new InProgress(
                    blueprint.get(),
                    new BlockPos(origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt()),
                    rotation,
                    clearSite,
                    gather,
                    scaffold));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Removes the in-progress file of a build that ended or was cancelled. */
    public void deleteInProgress(String id) {
        if (!validBuildId(id)) {
            return;
        }
        try {
            Files.deleteIfExists(inProgressFile(id));
        } catch (IOException ignored) {
            // A file that cannot be deleted is only stale data; the build itself is over.
        }
    }

    private static JsonArray numbers(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    /** Writes {@code text} to a temporary file in the same directory and moves it into place. */
    private static void writeAtomically(Path file, String prefix, String text) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), prefix, ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
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
