package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** The persisted anchor that lets a companion keep (and restore) its own loaded chunk. */
public class CompanionChunkLoadingTests {
    @GameTest
    public void summonRecordsAnAnchor(GameTestHelper h) {
        ServerPlayer player = CompanionEntityTests.mockPlayerHere(h);
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(player);
        h.runAfterDelay(5, () -> {
            var anchor = FriendaiServerState.get(h.getLevel().getServer())
                    .chunkLoader()
                    .anchorFor(player.getUUID());
            h.assertTrue(anchor.isPresent(), "an anchor should be recorded for the owner");
            h.assertTrue(anchor.get().chunk().equals(companion.chunkPosition()),
                    "the anchor should name the companion's chunk");
            h.succeed();
        });
    }

    @GameTest
    public void dismissDropsTheAnchor(GameTestHelper h) {
        ServerPlayer player = CompanionEntityTests.mockPlayerHere(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(player);
        h.runAfterDelay(5, () -> {
            manager.dismiss(player);
            h.runAfterDelay(5, () -> {
                var loader = FriendaiServerState.get(h.getLevel().getServer()).chunkLoader();
                h.assertTrue(loader.anchorFor(player.getUUID()).isEmpty(),
                        "dismissing the companion should drop its anchor");
                h.succeed();
            });
        });
    }
}
