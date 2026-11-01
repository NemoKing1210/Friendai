package dev.friendai.client.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.friendai.agent.llm.provider.ModelListCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** Model lists fetched from providers, kept for a day and persisted as a last-good fallback. */
public final class ModelCache {
    private static final Gson GSON = new Gson();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("friendai-models.json");
    private static final ModelListCache CACHE = new ModelListCache();

    static {
        load();
    }

    private ModelCache() {}

    public static List<String> get(String provider) {
        return CACHE.get(provider, System.currentTimeMillis());
    }

    public static void put(String provider, List<String> models) {
        CACHE.put(provider, models, System.currentTimeMillis());
        save();
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try {
            Map<String, List<String>> saved = GSON.fromJson(
                    Files.readString(FILE, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, List<String>>>() {}.getType());
            if (saved != null) {
                CACHE.restore(saved);
            }
        } catch (IOException | RuntimeException ignored) {
            // A corrupt cache is only a lost fallback; the provider fetch repopulates it.
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(CACHE.lastGood()), StandardCharsets.UTF_8);
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // The cache is a convenience, never a reason to fail a request.
        }
    }
}
