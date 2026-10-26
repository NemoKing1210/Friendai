package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.RequestType;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.net.CompanionRequestC2SPayload;
import dev.friendai.net.CompanionRequestHandler;
import dev.friendai.net.RateLimiter;
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
                .memory()
                .book(owner.getUUID())
                .companionName()
                .orElse("");
    }

    private static void rename(ServerPlayer owner, String name) {
        CompanionRequestHandler.handle(owner, new CompanionRequestC2SPayload(RequestType.RENAME, name, ""));
    }

    @GameTest
    public void rename_request_updates_book_and_entity(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);

        rename(owner, "  Alex   Jr ");

        h.assertTrue("Alex Jr".equals(storedName(h, owner)), "the book keeps the cleaned name");
        h.assertTrue("Alex Jr".equals(companion.getCustomName().getString()), "the name tag shows the new name");
        h.succeed();
    }

    @GameTest
    public void rename_blank_is_rejected(GameTestHelper h) {
        ServerPlayer owner = owner(h);
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
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
}
