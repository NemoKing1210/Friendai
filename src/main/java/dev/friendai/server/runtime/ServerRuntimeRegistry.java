package dev.friendai.server.runtime;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/** Lifecycle-only lookup for the runtime attached to each Minecraft server. */
public final class ServerRuntimeRegistry {

    private static final Map<MinecraftServer, ServerRuntime> RUNTIMES = new IdentityHashMap<>();

    private ServerRuntimeRegistry() {}

    public static synchronized ServerRuntime getOrCreate(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server is required");
        return RUNTIMES.computeIfAbsent(server, ServerRuntimeFactory::create);
    }

    public static synchronized Optional<ServerRuntime> find(MinecraftServer server) {
        return Optional.ofNullable(RUNTIMES.get(server));
    }

    /** Removes the runtime before closing it so repeated lifecycle callbacks cannot recreate or close it twice. */
    public static void removeAndClose(MinecraftServer server) {
        ServerRuntime runtime;
        synchronized (ServerRuntimeRegistry.class) {
            runtime = RUNTIMES.remove(server);
        }
        if (runtime != null) runtime.close();
    }
}
