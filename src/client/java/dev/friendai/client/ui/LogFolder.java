package dev.friendai.client.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Opens or copies the local log tree. Never throws: a machine without a desktop simply gets false. */
public final class LogFolder {
    private LogFolder() {}

    public static Path localRoot() {
        return dev.friendai.debug.LogPaths.gameRoot(FabricLoader.getInstance().getGameDir());
    }

    /**
     * The folder a player means by "my logs": the current world's tree in singleplayer, the client tree in the menu
     * or on a remote server, where this machine never holds the server's files.
     */
    public static Path currentRoot() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return localRoot();
        return dev.friendai.debug.LogPaths.worldRoot(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
    }

    /** Creates the folder and its README when missing, then opens it in the desktop file manager. */
    public static boolean open(Path path) {
        try {
            Files.createDirectories(path);
            Path readme = path.resolve("README.md");
            if (!Files.exists(readme)) {
                Files.writeString(readme, readmeText(), StandardCharsets.UTF_8);
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
                return true;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // No desktop, no permission or no file manager: the path is still shown in the tooltip.
        }
        return false;
    }

    public static boolean copy(Path path) {
        try {
            Minecraft.getInstance()
                    .keyboardHandler
                    .setClipboard(path.toAbsolutePath().toString());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String readmeText() {
        return """
                # Friendai logs

                One JSON object per line. The first line of every file is a header with the schema, boot, world,
                mod and Minecraft versions, the owner and the active level.

                A world keeps its own tree at `<world>/friendai/logs/`:
                - `server/<date>/boot-<boot>.jsonl` — server lifecycle and bridged logger warnings and errors.
                - `owners/<uuid>/<date>/session-<time>-<boot>.jsonl` — one owner's brain, tool, task and queue records.
                - `diagnostics/` — `/friendai diagnose` bundles.

                This client folder holds the client's own records in `client/<date>/`, and `index.jsonl`, one line per
                world load and close.

                Follow a chain backwards with `cause`: each line's `cause` is the `seq` of the record that caused it.
                `.md` files are generated digests and can be deleted; they are rebuilt from the JSONL.

                Example: `jq -c 'select(.task==42)' owners/<uuid>/<date>/*.jsonl`
                """;
    }
}
