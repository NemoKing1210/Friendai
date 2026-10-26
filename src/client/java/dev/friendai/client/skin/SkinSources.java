package dev.friendai.client.skin;

import dev.friendai.FriendaiMod;
import dev.friendai.skin.FetchedSkin;
import dev.friendai.skin.SkinFetcher;
import dev.friendai.skin.SkinImage;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The native file dialog; null when the player cancels. Throws {@link UserFacingException} when none can open. */
    private static Path chooseFile() {
        try {
            FileDialog dialog = new FileDialog(
                    (Frame) null, Component.translatable("friendai.menu.appearance.dialog_title").getString());
            dialog.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
            dialog.setMode(FileDialog.LOAD);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
            String file = dialog.getFile();
            dialog.dispose();
            return file == null ? null : Path.of(dialog.getDirectory(), file);
        } catch (HeadlessException | LinkageError e) {
            throw new UserFacingException(UserMessage.of(DIALOG_UNAVAILABLE));
        }
    }
}
