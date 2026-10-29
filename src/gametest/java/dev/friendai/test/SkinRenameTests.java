package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.RequestType;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.net.CompanionRequestC2SPayload;
import dev.friendai.net.CompanionRequestHandler;
import dev.friendai.net.SkinHandler;
import dev.friendai.net.SkinRequestC2SPayload;
import dev.friendai.net.SkinS2CPayload;
import dev.friendai.net.SkinUploadC2SPayload;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Menu rename and custom skins: the requests a player sends from the Appearance tab. */
public class SkinRenameTests {
    private static final int FLOOR = 60;

    private static ServerPlayer owner(GameTestHelper h) {
        GameTestSupport.platformAt(h, FLOOR, 16);
        CompanionRequestHandler.setLimiter(new RateLimiter(0, System::currentTimeMillis));
        return GameTestSupport.mockPlayerAt(h, 2, FLOOR + 1, 2);
    }

    private static String storedName(GameTestHelper h, ServerPlayer owner) {
        return FriendaiServerState.get(h.getLevel().getServer())
                .profiles()
                .profile(owner.getUUID())
                .name();
    }

    private static void rename(ServerPlayer owner, String name) {
        CompanionRequestHandler.handle(owner, new CompanionRequestC2SPayload(RequestType.RENAME, name, ""));
    }

    @GameTest
    public void rename_request_updates_book_and_entity(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);

        rename(owner, "  Alex   Jr ");

        h.assertTrue("Alex Jr".equals(storedName(h, owner)), "the book keeps the cleaned name");
        h.assertTrue("Alex Jr".equals(companion.getCustomName().getString()), "the name tag shows the new name");
        h.succeed();
    }

    @GameTest
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

    @GameTest
    public void rename_over_32_chars_is_rejected(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        rename(owner, "Mira");

        rename(owner, "x".repeat(33));

        h.assertTrue("Mira".equals(storedName(h, owner)), "a name over the limit must not replace the old one");
        h.succeed();
    }

    @GameTest
    public void rename_with_formatting_code_is_rejected(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        rename(owner, "Mira");

        rename(owner, "§cRed");

        h.assertTrue("Mira".equals(storedName(h, owner)), "formatting codes must be refused");
        h.succeed();
    }

    @GameTest
    public void rename_without_companion_changes_nothing(GameTestHelper h) {
        ServerPlayer owner = owner(h);

        rename(owner, "Ghost");

        h.assertTrue(storedName(h, owner).isEmpty(), "no companion means no stored name");
        h.succeed();
    }

    // --- skins ---

    private record Sent(UUID player, SkinS2CPayload payload) {}

    private static final List<Sent> SENT = new CopyOnWriteArrayList<>();

    private static ServerPlayer skinOwner(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        SkinHandler.setLimiter(new RateLimiter(0, System::currentTimeMillis));
        SkinHandler.setSender((player, payload) -> SENT.add(new Sent(player.getUUID(), payload)));
        return owner;
    }

    private static byte[] skinPng(int seed) {
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

    private static String canonicalHash(byte[] png) {
        return SkinHash.of(((SkinImage.Valid) SkinImage.validate(png)).png());
    }

    @GameTest
    public void skin_upload_sets_entity_hash_and_persists(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        byte[] png = skinPng(10);

        SkinHandler.upload(owner, new SkinUploadC2SPayload(true, png));

        h.assertTrue(canonicalHash(png).equals(companion.getSkinHash()), "the entity carries the skin hash");
        h.assertTrue(companion.isSlimSkin(), "the slim flag is synced");
        var stored = FriendaiServerState.get(h.getLevel().getServer()).skins().load(owner.getUUID());
        h.assertTrue(stored.isPresent() && stored.get().slim(), "the skin is stored for the owner");
        h.succeed();
    }

    @GameTest
    public void skin_upload_invalid_png_is_rejected(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        SkinHandler.upload(owner, new SkinUploadC2SPayload(false, skinPng(20)));
        String before = companion.getSkinHash();

        SkinHandler.upload(owner, new SkinUploadC2SPayload(false, new byte[] {1, 2, 3}));

        h.assertTrue(before.equals(companion.getSkinHash()), "a bad upload must not change the skin");
        h.assertTrue(!before.isEmpty(), "the earlier skin is still set");
        h.succeed();
    }

    @GameTest
    public void skin_upload_without_companion_is_ignored(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);

        SkinHandler.upload(owner, new SkinUploadC2SPayload(false, skinPng(25)));

        h.assertTrue(
                FriendaiServerState.get(h.getLevel().getServer())
                        .skins()
                        .load(owner.getUUID())
                        .isEmpty(),
                "nothing is stored when the player has no companion");
        h.succeed();
    }

    @GameTest
    public void skin_reset_clears_hash_and_store(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        SkinHandler.upload(owner, new SkinUploadC2SPayload(false, skinPng(30)));

        SkinHandler.upload(owner, new SkinUploadC2SPayload(false, new byte[0]));

        h.assertTrue(companion.getSkinHash().isEmpty(), "the hash is cleared");
        h.assertTrue(
                FriendaiServerState.get(h.getLevel().getServer())
                        .skins()
                        .load(owner.getUUID())
                        .isEmpty(),
                "the stored skin is removed");
        h.succeed();
    }

    @GameTest
    public void skin_request_for_foreign_hash_is_ignored(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        SkinHandler.upload(owner, new SkinUploadC2SPayload(false, skinPng(40)));
        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 3, FLOOR + 1, 3);

        SkinHandler.request(other, new SkinRequestC2SPayload(canonicalHash(skinPng(41))));
        SkinHandler.request(other, new SkinRequestC2SPayload("../../etc/passwd"));

        h.assertTrue(SENT.stream().noneMatch(s -> s.player().equals(other.getUUID())), "no skin may be sent");
        h.succeed();
    }

    @GameTest
    public void skin_request_for_current_hash_sends_png(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        byte[] png = skinPng(50);
        SkinHandler.upload(owner, new SkinUploadC2SPayload(true, png));
        ServerPlayer viewer = GameTestSupport.mockPlayerAt(h, 3, FLOOR + 1, 3);
        String hash = canonicalHash(png);

        SkinHandler.request(viewer, new SkinRequestC2SPayload(hash));

        var sent =
                SENT.stream().filter(s -> s.player().equals(viewer.getUUID())).toList();
        h.assertTrue(sent.size() == 1, "exactly one skin is sent");
        h.assertTrue(hash.equals(sent.get(0).payload().hash()), "it carries the requested hash");
        h.assertTrue(sent.get(0).payload().slim(), "it carries the slim flag");
        h.assertTrue(SkinHash.of(sent.get(0).payload().png()).equals(hash), "its bytes match the hash");
        h.succeed();
    }

    @GameTest
    public void skin_survives_respawn(GameTestHelper h) {
        ServerPlayer owner = skinOwner(h);
        var manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(owner);
        byte[] png = skinPng(60);
        SkinHandler.upload(owner, new SkinUploadC2SPayload(true, png));
        manager.dismiss(owner);

        CompanionEntity again = manager.summon(owner);

        h.assertTrue(canonicalHash(png).equals(again.getSkinHash()), "the new entity restores the stored skin");
        h.assertTrue(again.isSlimSkin(), "and its arm model");
        h.succeed();
    }
}
