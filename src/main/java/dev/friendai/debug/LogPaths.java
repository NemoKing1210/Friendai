package dev.friendai.debug;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * The one derivation from a world or game directory to a log path. The server writer, {@code logs_search}, the
 * diagnose bundle and the commands all resolve their root here, so isolation between worlds is a property of the
 * layout instead of a filter every call site has to remember.
 *
 * <p>A world owns its whole server-side tree: {@code <world>/friendai/logs/}. Nothing resolves the shared
 * {@code gameDir/logs/friendai} tree for server records, and nothing falls back to it when the world path is
 * unavailable — that case gets its own directory instead.
 */
public final class LogPaths {
    private static final String MOD_DIR = "friendai";
    private static final String LOGS_DIR = "logs";

    private LogPaths() {}

    /** The client-side tree and the world index, which outlive any one world. */
    public static Path gameRoot(Path gameDir) {
        return Objects.requireNonNull(gameDir, "gameDir").resolve(LOGS_DIR).resolve(MOD_DIR);
    }

    /** The server-side tree of one world. */
    public static Path worldRoot(Path worldPath) {
        return Objects.requireNonNull(worldPath, "worldPath").resolve(MOD_DIR).resolve(LOGS_DIR);
    }

    /** One owner's subtree inside a world, the only directory the companion may search. */
    public static Path ownerRoot(Path worldPath, UUID owner) {
        return worldRoot(worldPath)
                .resolve("owners")
                .resolve(Objects.requireNonNull(owner, "owner").toString());
    }

    /**
     * Where a writer goes when the world path cannot be resolved. Separate from {@link #worldRoot} and from
     * {@link #gameRoot} so a failure can never mix two worlds into one tree.
     */
    public static Path unavailableRoot(Path gameDir) {
        return gameRoot(gameDir).resolve("unavailable");
    }

    /**
     * What {@code /friendai logs path} prints: an operator sees the world's tree, a player sees only their own
     * subtree. The decision lives here so the command stays a three-line adapter.
     */
    public static Path commandPath(Path worldPath, UUID owner, boolean operator) {
        return operator ? worldRoot(worldPath) : ownerRoot(worldPath, owner);
    }

    /** The world directory name, used as the {@code world} field of a file header; empty when unknown. */
    public static String worldName(Path worldPath) {
        if (worldPath == null) return "";
        Path name = worldPath.getFileName();
        return name == null ? "" : name.toString();
    }
}
