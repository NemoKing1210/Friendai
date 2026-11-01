package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.social.BondLedger;
import dev.friendai.features.social.Mood;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** The bond counts real deeds, survives death and never changes a permission. */
public class SocialBondTests {
    private static ServerPlayer ready(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        return GameTestSupport.mockPlayerAt(h, 2, 2, 2);
    }

    @GameTest(maxTicks = 300)
    public void sharedWorkRaisesTheBondAndAnOwnerHitLowersIt(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());

        companion.noteSharedWork();
        companion.noteSharedWork();
        h.assertValueEqual(
                runtime.memory()
                        .book(owner.getUUID())
                        .bond(owner.getUUID())
                        .orElseThrow()
                        .points(),
                4,
                "two finished owner jobs award a shared-work point each");
        h.assertValueEqual(companion.syncedMood(), Mood.PLEASED, "shared work pleases");

        companion.noteOwnerHit();
        h.assertValueEqual(
                runtime.memory()
                        .book(owner.getUUID())
                        .bond(owner.getUUID())
                        .orElseThrow()
                        .points(),
                2,
                "an owner hit costs two points");
        h.assertValueEqual(companion.syncedMood(), Mood.DISPLEASED, "an owner hit displeases");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void theBondSurvivesDeathAndRespawn(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion.noteSharedWork();
        companion.noteSharedWork();
        UUID dead = companion.getUUID();
        int before = runtime.memory()
                .book(owner.getUUID())
                .bond(owner.getUUID())
                .orElseThrow()
                .points();

        companion.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000.0f);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = manager.find(owner.getUUID())
                    .filter(entity -> !entity.getUUID().equals(dead));
            h.assertTrue(back.isPresent(), "the companion should respawn");
            h.assertValueEqual(
                    runtime.memory()
                            .book(owner.getUUID())
                            .bond(owner.getUUID())
                            .orElseThrow()
                            .points(),
                    before,
                    "death must not touch the bond");
            h.assertTrue(
                    back.get().bondTier() == BondLedger.Tier.STRANGER,
                    "a small bond stays at its tier, but it is read from the book");
        });
    }

    @GameTest(maxTicks = 200)
    public void dangerShowsAWorriedFaceAndCalmReturns(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertValueEqual(companion.syncedMood(), Mood.CALM, "a fresh companion is calm");

        companion.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), companion.getHealth() * 0.7f);
        h.runAfterDelay(30, () -> {
            h.assertValueEqual(companion.syncedMood(), Mood.WORRIED, "danger must show after its window");
            companion.setHealth(companion.getMaxHealth());
            h.runAfterDelay(
                    40,
                    () -> h.succeedWhen(() -> h.assertTrue(
                            companion.syncedMood() != Mood.WORRIED,
                            "the face leaves worry once the danger is gone: " + companion.syncedMood())));
        });
    }
}
