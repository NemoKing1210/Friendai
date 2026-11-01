package dev.friendai.test;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.social.BondLedger;
import dev.friendai.features.social.BondPrompt;
import dev.friendai.features.social.Mood;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.net.SnapshotBuilder;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** The bond counts real deeds, survives death and never changes a permission. */
public class SocialBondTests {
    private static ServerPlayer ready(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        return GameTestSupport.mockPlayerAt(h, 2, 2, 2);
    }

    @GameTest(maxTicks = 300)
    public void aMannerTheOwnerRejectsIsDroppedAndNeverDriftsBack(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);

        companion.driftTrait();
        h.assertValueEqual(
                companion.traits().of(dev.friendai.features.social.Traits.Kind.HUMOUR), 1, "drift starts with humour");
        companion.rejectManner(dev.friendai.features.social.Traits.Kind.HUMOUR);
        h.assertValueEqual(
                companion.traits().of(dev.friendai.features.social.Traits.Kind.HUMOUR),
                0,
                "a rejected manner drops at once");

        for (int i = 0; i < 8; i++) {
            companion.driftTrait();
        }
        h.assertValueEqual(
                companion.traits().of(dev.friendai.features.social.Traits.Kind.HUMOUR),
                0,
                "drift never brings a rejected manner back: " + companion.traits());
        h.assertTrue(
                companion.traits().of(dev.friendai.features.social.Traits.Kind.WARMTH) > 0,
                "the other manners keep drifting: " + companion.traits());
        h.assertTrue(
                !companion
                        .traits()
                        .toneLine()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("humour"),
                "the prompt no longer asks for the rejected manner: "
                        + companion.traits().toneLine());
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void aMoodChangeIsTracedWithTheInputsThatCausedIt(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());

        companion.setHealth(companion.getMaxHealth() * 0.4f);
        h.succeedWhen(() -> {
            var traced = runtime.sessions().debug().logOf(owner.getUUID()).after(0, 200).stream()
                    .map(entry -> entry.text())
                    .filter(text -> text.startsWith("mood "))
                    .toList();
            h.assertTrue(
                    traced.stream().anyMatch(text -> text.contains("health=")),
                    "every traced mood change names the health behind it: " + traced);
            h.assertTrue(
                    traced.stream().anyMatch(text -> text.contains(" -> WORRIED")),
                    "hurt below half health worries: " + traced);
        });
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

    @GameTest(maxTicks = 300)
    public void aGiftPaysOncePerKindAndARescueIsRemembered(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());

        companion.noteGift("oak_log", 5);
        h.assertValueEqual(
                runtime.memory()
                        .book(owner.getUUID())
                        .bond(owner.getUUID())
                        .orElseThrow()
                        .points(),
                3,
                "a gift past the previous peak is worth three");
        companion.noteGift("oak_log", 5);
        h.assertValueEqual(
                runtime.memory()
                        .book(owner.getUUID())
                        .bond(owner.getUUID())
                        .orElseThrow()
                        .points(),
                3,
                "the same balance again pays nothing");

        // A rescue is worth five, and the daily cap still governs: three points already spent leaves no room for it.
        companion.noteRescue();
        h.assertValueEqual(
                runtime.memory()
                        .book(owner.getUUID())
                        .bond(owner.getUUID())
                        .orElseThrow()
                        .points(),
                3,
                "the daily cap refuses a rescue that would pass six points");

        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity second = CompanionManager.get(h.getLevel().getServer()).summon(other);
        second.noteRescue();
        var bond = runtime.memory().book(other.getUUID()).bond(other.getUUID()).orElseThrow();
        h.assertValueEqual(bond.points(), 5, "a rescue is worth five");
        h.assertTrue(
                bond.reasons().getFirst().contains("rescue"),
                "the newest reason must be the rescue, got " + bond.reasons());
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

    @GameTest(maxTicks = 300)
    public void theDriftedMannerIsKeptInTheBook(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);

        companion.driftTrait();
        companion.driftTrait();

        var drifted = runtime.memory().book(owner.getUUID()).traits(owner.getUUID());
        h.assertValueEqual(
                drifted.of(dev.friendai.features.social.Traits.Kind.HUMOUR)
                        + drifted.of(dev.friendai.features.social.Traits.Kind.WARMTH)
                        + drifted.of(dev.friendai.features.social.Traits.Kind.CURIOSITY)
                        + drifted.of(dev.friendai.features.social.Traits.Kind.ENERGY)
                        + drifted.of(dev.friendai.features.social.Traits.Kind.TEASING),
                2,
                "two compactions make two small steps");
        h.assertTrue(!drifted.toneLine().isBlank(), "a drifted manner must reach the prompt");

        CompanionEntity fresh = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        fresh.setOwner(owner.getUUID());
        h.assertValueEqual(fresh.traits(), drifted, "a new body reads the same manner from the book");
        h.succeed();
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

    @GameTest(maxTicks = 300)
    public void theBondAndMannerSurviveASaveAndLoad(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.noteSharedWork();
        companion.noteSharedWork();
        companion.driftTrait();
        companion.driftTrait();

        BondLedger.Tier tier = companion.bondTier();
        List<String> reasons = companion.bondReasons();
        var traits = companion.traits();
        h.assertTrue(!reasons.isEmpty(), "the saved companion has no deed behind it, so nothing is proved");

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        companion.saveWithoutId(output);
        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        restored.load(
                TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));

        // The body save carries only the owner; the bond and the manner are read back from the book under that owner.
        // Four points stay below ACQUAINTANCE, so the tier alone would read STRANGER even for a lost bond: the exact
        // reasons and the exact drifted traits are what prove the ledger and the drift came through the round trip.
        h.assertValueEqual(restored.bondTier(), tier, "the bond tier survives a save and load");
        h.assertValueEqual(restored.bondReasons(), reasons, "the bond reasons survive a save and load");
        h.assertValueEqual(restored.traits(), traits, "the drifted manner survives a save and load");
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void aGuestCannotReadOrChangeTheBond(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());

        // Both hooks must exist before the brain is created, which the first turn does.
        AtomicReference<String> guestSystem = new AtomicReference<>();
        runtime.sessions().setGatewayOverride(owner.getUUID(), (system, messages, tools) -> {
            // An ambient or owner turn is not what this test measures; only the guest's own line is recorded.
            if (messages.stream().anyMatch(message -> message.text().contains("(guest)]: "))) {
                guestSystem.set(system);
            }
            return CompletableFuture.completedFuture(
                    new LlmResponse("Noted.", List.of(), StopReason.END, new Usage(0, 0)));
        });
        runtime.sessions().setSpeakerWrapper(owner.getUUID(), real -> new Speaker() {
            @Override
            public void say(String text) {}

            @Override
            public void thinking(boolean on) {}

            @Override
            public void error(UserMessage message) {}
        });

        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.noteSharedWork();
        companion.noteSharedWork();

        BondLedger.Tier tier = companion.bondTier();
        List<String> reasons = companion.bondReasons();
        int points = runtime.memory()
                .book(owner.getUUID())
                .bond(owner.getUUID())
                .orElseThrow()
                .points();
        h.assertTrue(!reasons.isEmpty(), "the owner's bond has nothing to leak, so the guest check proves nothing");

        // A menu snapshot resolves a companion by the player who asks, never by a target that player names. The
        // owner's own snapshot carries the bond, so the guest's empty one is the guard holding, not an empty ledger.
        CompanionSnapshot ownerMenu = SnapshotBuilder.build(runtime, owner, 0L);
        h.assertValueEqual(
                ownerMenu.bondTier(),
                tier.name().toLowerCase(java.util.Locale.ROOT),
                "the owner's menu must show the bond tier");
        h.assertValueEqual(ownerMenu.bondReasons(), reasons, "the owner's menu must show the bond reasons");
        CompanionSnapshot guestMenu = SnapshotBuilder.build(runtime, guest, 0L);
        h.assertTrue(
                guestMenu.bondTier().isEmpty(),
                "a guest's menu carried the owner's bond tier: " + guestMenu.bondTier());
        h.assertTrue(
                guestMenu.bondReasons().isEmpty(),
                "a guest's menu carried the owner's bond reasons: " + guestMenu.bondReasons());

        // CompanionSessions turns bond facts into prompt text through this one function and tells it whether the
        // caller is the owner. The entity accessors are permission-blind by design (a bond never gates an action), so
        // this gate is the guard: the very facts that render a line for the owner render nothing for a guest.
        String ownerLine = BondPrompt.line(tier, companion.syncedMood(), reasons, true);
        h.assertTrue(
                ownerLine.contains("remembered: " + reasons.getFirst()),
                "the same facts must render a line for the owner, got " + ownerLine);
        h.assertTrue(
                BondPrompt.line(tier, companion.syncedMood(), reasons, false).isEmpty(),
                "the bond facts must render no line for a guest");

        // A real guest turn through the router must neither see the bond in its prompt nor move it.
        ChatRouter.onChat(runtime, guest, "Friendai, hi");

        h.succeedWhen(() -> {
            String system = guestSystem.get();
            h.assertTrue(system != null, "the guest turn never reached the owner's companion");
            h.assertTrue(!system.contains("remembered:"), "the guest's prompt carried the owner's bond: " + system);
            h.assertValueEqual(
                    runtime.memory()
                            .book(owner.getUUID())
                            .bond(owner.getUUID())
                            .orElseThrow()
                            .points(),
                    points,
                    "a guest turn moved the bond");
            h.assertValueEqual(companion.bondTier(), tier, "a guest turn moved the bond tier");
            h.assertValueEqual(companion.bondReasons(), reasons, "a guest turn changed the bond reasons");
        });
    }
}
