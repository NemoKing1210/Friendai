package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.transport.payload.SkinRequestC2SPayload;
import dev.friendai.transport.payload.SkinS2CPayload;
import dev.friendai.transport.payload.SkinUploadC2SPayload;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Menu rename and custom skins: the requests a player sends from the Appearance tab. */
public class SkinRenameTests {
    private static final int FLOOR = 60;

    private static ServerPlayer owner(GameTestHelper h) {
        GameTestSupport.platformAt(h, FLOOR, 16);
        ServerRuntimeRegistry.getOrCreate(h.getLevel().getServer())
                .protocolConnections()
                .setControlLimiterForTest(new RateLimiter(0, System::currentTimeMillis));
        return GameTestSupport.mockPlayerAt(h, 2, FLOOR + 1, 2);
    }

    /** The stored companion name; an owner who never named a companion has none, which reads as empty. */
    private static String storedName(GameTestHelper h, ServerPlayer owner) {
        String name = GameTestSupport.runtime(h.getLevel().getServer())
                .profiles()
                .profile(owner.getUUID())
                .name();
        return name == null ? "" : name;
    }

    private static void rename(ServerPlayer owner, String name) {
        TestMenuRequests.handle(owner, TestMenuRequests.menuRequest(TestMenuRequests.Action.RENAME, name, ""));
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void rename_request_updates_book_and_entity(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);

        rename(owner, "  Alex   Jr ");

        h.assertTrue("Alex Jr".equals(storedName(h, owner)), "the book keeps the cleaned name");
        h.assertTrue("Alex Jr".equals(companion.getCustomName().getString()), "the name tag shows the new name");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void rename_blank_is_rejected(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        rename(owner, "Mira");

        rename(owner, "   ");

        h.assertTrue("Mira".equals(storedName(h, owner)), "a blank name must not replace the old one");
        h.assertTrue("Mira".equals(companion.getCustomName().getString()), "the name tag keeps the old name");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void rename_over_32_chars_is_rejected(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        rename(owner, "Mira");

        rename(owner, "x".repeat(33));

        h.assertTrue("Mira".equals(storedName(h, owner)), "a name over the limit must not replace the old one");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void rename_with_formatting_code_is_rejected(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        rename(owner, "Mira");

        rename(owner, "§cRed");

        h.assertTrue("Mira".equals(storedName(h, owner)), "formatting codes must be refused");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void rename_without_companion_changes_nothing(GameTestHelper h) {
        ServerPlayer owner = owner(h);

        rename(owner, "Ghost");

        h.assertTrue(storedName(h, owner).isEmpty(), "no companion means no stored name");
        h.succeed();
    }

    // --- skins ---

    private record Sent(ServerPlayer player, SkinS2CPayload payload) {}

    private static final List<Sent> SENT = new ArrayList<>();

    private static ServerPlayer skinOwner(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        ServerRuntime runtime = GameTestSupport.runtime(h);
        runtime.protocolConnections().setSkinLimiterForTest(new RateLimiter(0, System::currentTimeMillis));
        runtime.skinHandler().setSenderForTest((player, payload) -> SENT.add(new Sent(player, payload)));
        return owner;
    }

    static byte[] skinPng(int seed) {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    image.setRGB(x, y, 0xFF000000 | ((x * 4 + seed) << 16) | (y * 4 << 8) | seed);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonicalHash(byte[] png) {
        return SkinHash.of(((SkinImage.Valid) SkinImage.validate(png)).png());
    }

    private static void upload(GameTestHelper h, ServerPlayer owner, byte[] png, boolean slim) {
        GameTestSupport.runtime(h).skinHandler().upload(owner, new SkinUploadC2SPayload(slim, png));
    }

    private static void request(GameTestHelper h, ServerPlayer viewer, String hash) {
        GameTestSupport.runtime(h).skinHandler().request(viewer, new SkinRequestC2SPayload(hash));
    }

    /** A writer barrier schedules its callback after all prior appearance callbacks on the server thread. */
    private static void afterSkinWork(GameTestHelper h, Runnable assertions) {
        ServerRuntime runtime = GameTestSupport.runtime(h);
        AtomicBoolean done = new AtomicBoolean();
        h.assertTrue(
                runtime.storeWriter().trySubmit(() -> runtime.server().execute(() -> done.set(true))),
                "appearance worker should accept the test barrier");
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(done.get(), "appearance worker has not finished"))
                .thenExecute(assertions);
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_upload_sets_entity_hash_and_persists(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion = GameTestSupport.runtime(h).companions().summon(owner);
        byte[] png = skinPng(10);

        upload(h, owner, png, true);
        h.assertTrue(companion.getSkinHash().isEmpty(), "upload must return before publishing the hash");

        afterSkinWork(h, () -> {
            h.assertValueEqual(companion.getSkinHash(), canonicalHash(png), "the entity carries the skin hash");
            h.assertTrue(companion.isSlimSkin(), "the slim flag is synced");
            var stored = GameTestSupport.runtime(h).skins().load(owner.getUUID());
            h.assertTrue(stored.isPresent() && stored.get().slim(), "the skin is stored for the owner");
            h.succeed();
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_upload_invalid_png_is_rejected(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion = GameTestSupport.runtime(h).companions().summon(owner);
        byte[] original = skinPng(20);
        upload(h, owner, original, true);
        upload(h, owner, new byte[] {1, 2, 3}, false);

        afterSkinWork(h, () -> {
            h.assertValueEqual(companion.getSkinHash(), canonicalHash(original), "a bad upload keeps the prior hash");
            h.assertTrue(companion.isSlimSkin(), "a bad upload keeps the prior model");
            h.succeed();
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_upload_without_companion_is_ignored(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        upload(h, owner, skinPng(25), false);

        afterSkinWork(h, () -> {
            h.assertTrue(
                    GameTestSupport.runtime(h).skins().load(owner.getUUID()).isEmpty(),
                    "nothing is stored when the player has no companion");
            h.succeed();
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_reset_clears_hash_and_store(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion = GameTestSupport.runtime(h).companions().summon(owner);
        upload(h, owner, skinPng(30), true);
        upload(h, owner, new byte[0], false);

        afterSkinWork(h, () -> {
            h.assertTrue(companion.getSkinHash().isEmpty(), "the hash is cleared");
            h.assertFalse(companion.isSlimSkin(), "the model is cleared");
            h.assertTrue(
                    GameTestSupport.runtime(h).skins().load(owner.getUUID()).isEmpty(), "the stored skin is removed");
            h.succeed();
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_request_for_foreign_hash_is_ignored(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        GameTestSupport.runtime(h).companions().summon(owner);
        upload(h, owner, skinPng(40), false);
        ServerPlayer viewer = GameTestSupport.onlinePlayer(h, "skin-foreign", 3, FLOOR + 1, 3, true)
                .player();

        afterSkinWork(h, () -> {
            request(h, viewer, canonicalHash(skinPng(41)));
            request(h, viewer, "../../etc/passwd");
            afterSkinWork(h, () -> {
                h.assertTrue(replies(viewer).isEmpty(), "no skin may be sent");
                h.getLevel().getServer().getPlayerList().remove(viewer);
                h.succeed();
            });
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_request_for_current_hash_sends_png_to_each_viewer(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        GameTestSupport.runtime(h).companions().summon(owner);
        byte[] png = skinPng(50);
        upload(h, owner, png, true);
        ServerPlayer first = GameTestSupport.onlinePlayer(h, "skin-view-a", 3, FLOOR + 1, 3, true)
                .player();
        ServerPlayer second = GameTestSupport.onlinePlayer(h, "skin-view-b", 4, FLOOR + 1, 3, true)
                .player();
        String hash = canonicalHash(png);

        afterSkinWork(h, () -> {
            request(h, first, hash);
            request(h, second, hash);
            afterSkinWork(h, () -> {
                assertReply(h, first, hash, true);
                assertReply(h, second, hash, true);
                h.getLevel().getServer().getPlayerList().remove(first);
                h.getLevel().getServer().getPlayerList().remove(second);
                h.succeed();
            });
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_request_drops_superseded_revision(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion = GameTestSupport.runtime(h).companions().summon(owner);
        byte[] original = skinPng(51);
        byte[] replacement = skinPng(52);
        upload(h, owner, original, true);
        ServerPlayer viewer = GameTestSupport.onlinePlayer(h, "skin-replace", 3, FLOOR + 1, 3, true)
                .player();

        afterSkinWork(h, () -> {
            request(h, viewer, canonicalHash(original));
            upload(h, owner, replacement, false);
            upload(h, owner, new byte[] {1, 2, 3}, true);
            afterSkinWork(h, () -> {
                String expected = canonicalHash(replacement);
                h.assertTrue(replies(viewer).isEmpty(), "a request in flight cannot serve a superseded revision");
                h.assertValueEqual(companion.getSkinHash(), expected, "the newest valid replacement wins");
                h.assertFalse(companion.isSlimSkin(), "the replacement has its own arm model");
                request(h, viewer, expected);
                afterSkinWork(h, () -> {
                    assertReply(h, viewer, expected, false);
                    h.getLevel().getServer().getPlayerList().remove(viewer);
                    h.succeed();
                });
            });
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_request_drops_reply_after_reconnect(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        ServerRuntime runtime = GameTestSupport.runtime(h);
        runtime.companions().summon(owner);
        byte[] png = skinPng(53);
        upload(h, owner, png, true);
        ServerPlayer oldViewer = GameTestSupport.onlinePlayer(h, "skin-reconnect", 3, FLOOR + 1, 3, true)
                .player();
        String hash = canonicalHash(png);

        afterSkinWork(h, () -> {
            request(h, oldViewer, hash);
            runtime.protocolConnections().leave(oldViewer);
            runtime.server().getPlayerList().remove(oldViewer);
            ServerPlayer newViewer = GameTestSupport.onlinePlayer(h, oldViewer.getGameProfile(), 3, FLOOR + 1, 3)
                    .player();
            request(h, newViewer, hash);
            afterSkinWork(h, () -> {
                h.assertTrue(replies(oldViewer).isEmpty(), "old connection gets no reply");
                assertReply(h, newViewer, hash, true);
                runtime.server().getPlayerList().remove(newViewer);
                h.succeed();
            });
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void skin_survives_respawn(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        var manager = GameTestSupport.runtime(h).companions();
        manager.summon(owner);
        byte[] png = skinPng(60);
        upload(h, owner, png, true);
        manager.dismiss(owner);
        CompanionEntity again = manager.summon(owner);

        afterSkinWork(h, () -> {
            h.assertValueEqual(again.getSkinHash(), canonicalHash(png), "the new entity restores the stored skin");
            h.assertTrue(again.isSlimSkin(), "and its arm model");
            h.succeed();
        });
    }

    private static List<SkinS2CPayload> replies(ServerPlayer viewer) {
        return SENT.stream()
                .filter(s -> s.player() == viewer)
                .map(Sent::payload)
                .toList();
    }

    private static void assertReply(GameTestHelper h, ServerPlayer viewer, String hash, boolean slim) {
        var sent = replies(viewer);
        h.assertTrue(sent.size() == 1, "exactly one skin is sent");
        h.assertValueEqual(sent.getFirst().hash(), hash, "it carries the requested hash");
        h.assertValueEqual(sent.getFirst().slim(), slim, "it carries the matching arm model");
        h.assertValueEqual(SkinHash.of(sent.getFirst().png()), hash, "its bytes match the hash");
    }
}
