package dev.friendai.client.skin;

import dev.friendai.FriendaiMod;
import dev.friendai.skin.FetchedSkin;
import dev.friendai.skin.SkinFetcher;
import dev.friendai.skin.SkinImage;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Where a skin can come from: a file on disk, a Minecraft username or a URL. Every source runs on a worker thread
 * and reports back on the client thread with either a validated canonical PNG or a message for the player.
 */
public final class SkinSources {
    /** A skin ready to upload. {@code slim} is the profile's own arm model, or null when the source has none. */
    public record Picked(byte[] png, Boolean slim) {}

    /** Exactly one of the two is non-null. */
    public record Outcome(Picked picked, Component error) {}

    private static final String DIALOG_UNAVAILABLE = "friendai.menu.appearance.dialog_unavailable";
    private static final SkinFetcher FETCHER = SkinFetcher.mojang(SkinFetcher.newClient());

    private SkinSources() {}

    public static void fromFile(Consumer<Outcome> done) {
        async(done, () -> {
            Path path = chooseFile();
            if (path == null) {
                return null;
            }
            if (Files.size(path) > SkinImage.MAX_INPUT) {
                return failed(SkinFetcher.TOO_LARGE);
            }
            return check(Files.readAllBytes(path), null);
        });
    }

    public static void fromUsername(String name, Consumer<Outcome> done) {
        async(done, () -> {
            FetchedSkin skin = FETCHER.byUsername(name == null ? "" : name.strip());
            return check(skin.png(), skin.slim());
        });
    }

    public static void fromUrl(String url, Consumer<Outcome> done) {
        async(done, () -> check(FETCHER.byUrl(url), null));
    }

    private interface Job {
        /** Null means the player cancelled; nothing is reported. */
        Outcome run() throws IOException;
    }

    private static void async(Consumer<Outcome> done, Job job) {
        Thread worker = new Thread(() -> {
            Outcome outcome;
            try {
                outcome = job.run();
            } catch (UserFacingException e) {
                outcome = new Outcome(null, Component.translatable(e.userMessage().key()));
            } catch (IOException | RuntimeException e) {
                FriendaiMod.LOGGER.warn("Reading a skin failed: {}", e.getMessage());
                outcome = failed(SkinFetcher.NETWORK);
            }
            if (outcome != null) {
                Outcome result = outcome;
                Minecraft.getInstance().execute(() -> done.accept(result));
            }
        }, "friendai-skin-source");
        worker.setDaemon(true);
        worker.start();
    }

    private static Outcome check(byte[] png, Boolean slim) {
        SkinImage.Result result = SkinImage.validate(png);
        if (result instanceof SkinImage.Valid valid) {
            return new Outcome(new Picked(valid.png(), slim), null);
        }
        String key = switch (((SkinImage.Rejected) result).problem()) {
            case TOO_LARGE, RE_ENCODED_TOO_LARGE -> SkinFetcher.TOO_LARGE;
            case NOT_PNG -> "friendai.skin.error.not_png";
            case WRONG_SIZE -> "friendai.skin.error.wrong_size";
        };
        return failed(key);
    }

    private static Outcome failed(String key) {
        return new Outcome(null, Component.translatable(key));
    }

    /**
     * The system's own file dialog, started as a separate process: AWT cannot show one inside the game. Returns null
     * when the player cancels and throws {@link UserFacingException} when no dialog program exists.
     */
    private static Path chooseFile() throws IOException {
        String title = Component.translatable("friendai.menu.appearance.dialog_title").getString();
        String filter = Component.translatable("friendai.menu.appearance.dialog_filter").getString();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<List<String>> candidates;
        if (os.contains("win")) {
            String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                    + "Add-Type -AssemblyName System.Windows.Forms;"
                    + "$d=New-Object System.Windows.Forms.OpenFileDialog;"
                    + "$d.Title='" + title.replace("'", "''") + "';"
                    + "$d.Filter='" + filter.replace("'", "''") + " (*.png)|*.png';"
                    + "if($d.ShowDialog() -eq 'OK'){[Console]::Out.Write($d.FileName)}";
            candidates = List.of(List.of("powershell", "-NoProfile", "-STA", "-Command", script));
        } else if (os.contains("mac")) {
            String script = "POSIX path of (choose file of type {\"png\"} with prompt \""
                    + title.replace("\\", "").replace("\"", "") + "\")";
            candidates = List.of(List.of("osascript", "-e", script));
        } else {
            candidates = List.of(
                    List.of("zenity", "--file-selection", "--title=" + title, "--file-filter=*.png"),
                    List.of("kdialog", "--title", title, "--getopenfilename", ".", "*.png"));
        }
        for (List<String> command : candidates) {
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(false).start();
            } catch (IOException e) {
                continue;
            }
            try {
                process.getOutputStream().close();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return output.isEmpty() ? null : Path.of(output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return null;
            }
        }
        throw new UserFacingException(UserMessage.of(DIALOG_UNAVAILABLE));
    }
}
