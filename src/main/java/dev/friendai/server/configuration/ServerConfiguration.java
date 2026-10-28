package dev.friendai.server.configuration;

import dev.friendai.config.ServerConfig;
import java.util.List;
import java.util.function.Consumer;

/** Publishes complete immutable settings snapshots for one Minecraft server. */
public final class ServerConfiguration {

    private final Consumer<String> unknownBreakEntry;
    private volatile ServerSettings current = ServerSettings.DEFAULTS;

    public ServerConfiguration(Consumer<String> unknownBreakEntry) {
        this.unknownBreakEntry = unknownBreakEntry == null ? ignored -> {} : unknownBreakEntry;
    }

    public ServerSettings current() {
        return current;
    }

    /** Validate, resolve and publish on the owning server thread. */
    public List<String> apply(ServerConfig config) {
        List<String> problems = config.validate();
        ServerSettings resolved = ServerSettingsResolver.resolve(config, unknownBreakEntry);
        current = resolved;
        return problems;
    }
}
