package dev.friendai.test;

import com.google.gson.*;
import dev.friendai.agent.llm.model.*;
import dev.friendai.agent.turn.*;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.*;
import dev.friendai.features.instructions.*;
import dev.friendai.infrastructure.persistence.instructions.SkillStore;
import dev.friendai.protocol.instructions.InstructionRequest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;

/** Runtime coverage of authenticated menu actions, prompt refresh and persisted model approval. */
public class SkillsTests {
    @GameTest(maxTicks = 100)
    public void editorReopensSubscriptionWithinTheRateLimit(GameTestHelper h) {
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        TestMenuRequests.handle(player, new InstructionRequest.Open(), 1);
        TestMenuRequests.handle(player, new InstructionRequest.Close(), 2);
        TestMenuRequests.handle(player, new InstructionRequest.Open(), 3);
        h.assertTrue(
                dev.friendai.server.net.SkillsViewStream.isOpen(player),
                "switching from catalogue to editor must register the new subscription even in the same tick");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void longImportIsBoundToAuthenticatedSender(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var other = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        JsonObject input = new JsonObject();
        input.addProperty("name", "Long instruction");
        input.addProperty("body", "😀".repeat(2000));
        input.addProperty("owner", other.getUUID().toString());
        input.addProperty("source", "builtin");
        input.addProperty("scope", "server");
        var result = TestMenuRequests.applySkill(owner, "import", input.toString());
        h.assertTrue(result.status().ok(), "long imported instruction should fit the dedicated endpoint");
        h.assertTrue(
                state.skills().store().book(owner.getUUID()).snapshot().size() == 1, "sender owns the imported record");
        h.assertTrue(
                state.skills().store().book(other.getUUID()).snapshot().isEmpty(),
                "submitted owner must not redirect the write");
        var guest = new SkillActor(owner.getUUID(), new Caller(other.getUUID(), "Guest", AccessLevel.GUEST), false);
        var denied = state.skills()
                .actions()
                .apply(guest, SkillAction.create(SkillDraft.simple("Forged", "Inspect facts.")));
        h.assertTrue(!denied.status().ok(), "guest cannot write the owner's instructions");
        h.succeed();
    }

    @GameTest(maxTicks = 120)
    public void pendingApprovalAndPersistenceAdapter(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        var state = GameTestSupport.runtime(server);
        var owner = player.getUUID();
        var s = state.skills()
                .store()
                .book(owner)
                .create(
                        SkillDraft.simple("Learned procedure", "APPROVAL_SENTINEL"),
                        InstructionSkill.Source.MODEL,
                        owner,
                        1,
                        true);
        var caller = new Caller(owner, "Owner", AccessLevel.OWNER);
        var before = state.skills().begin(owner, caller, "hello", TurnKind.PLAYER, "", false, false);
        h.assertTrue(
                before.sections().stream().noneMatch(section -> section.id().equals("skill:" + s.id())),
                "pending procedure must not reach the prompt");
        JsonObject input = new JsonObject();
        input.addProperty("id", s.id());
        input.addProperty("expectedVersion", 1);
        h.assertTrue(
                TestMenuRequests.applySkill(player, "approve", input.toString())
                        .status()
                        .ok(),
                "owner approval should be accepted");
        var after = state.skills().begin(owner, caller, "hello", TurnKind.PLAYER, "", false, false);
        h.assertTrue(
                after.sections().stream().anyMatch(section -> section.id().equals("skill:" + s.id())),
                "approved procedure reaches the next turn");
        state.skills().store().flushAll();
        var dir = server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skills");
        h.succeedWhen(() -> {
            // flushAll only queues the snapshot; wait for the writer before reopening the store.
            if (state.skills().store().status(owner).dirty()) return;
            try (var reloaded = new SkillStore(dir, SkillLimits.defaults(), text -> text)) {
                h.assertTrue(
                        reloaded.book(owner).find(s.id()).orElseThrow().activation()
                                == InstructionSkill.Activation.ACTIVE,
                        "approval survives disk round-trip");
            }
        });
    }

    // GameTest ticks are faster than the brain's 500 ms wall-clock turn cooldown.
    @GameTest(maxTicks = 3600)
    public void liveToggleChangesNextGatewayRequest(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        var state = GameTestSupport.runtime(server);
        var owner = player.getUUID();
        var skill = state.skills()
                .store()
                .book(owner)
                .create(
                        SkillDraft.simple("Live instruction", "LIVE_TOGGLE_SENTINEL"),
                        InstructionSkill.Source.OWNER,
                        owner,
                        1,
                        false);
        List<String> systems = new ArrayList<>();
        state.sessions().setGatewayOverride(owner, (system, messages, tools) -> {
            systems.add(system);
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        });
        CompanionManager.get(server).summon(player);
        state.sessions().post(owner, new BrainEvent.PlayerSaid(owner, "Owner", "hello", BrainEvent.Source.CHAT));
        boolean[] toggled = {false};
        h.succeedWhen(() -> {
            if (!systems.isEmpty() && !toggled[0]) {
                h.assertTrue(
                        systems.getFirst().contains("LIVE_TOGGLE_SENTINEL"),
                        "enabled procedure reaches actual provider request");
                JsonObject input = new JsonObject();
                input.addProperty("id", skill.id());
                input.addProperty("expectedVersion", 1);
                h.assertTrue(
                        TestMenuRequests.applySkill(player, "disable", input.toString())
                                .status()
                                .ok(),
                        "live disable should succeed");
                toggled[0] = true;
                state.sessions()
                        .post(owner, new BrainEvent.PlayerSaid(owner, "Owner", "again", BrainEvent.Source.CHAT));
            }
            h.assertTrue(systems.size() >= 2, "waiting for second request");
            h.assertTrue(!systems.get(1).contains("LIVE_TOGGLE_SENTINEL"), "next turn omits disabled procedure");
        });
    }

    /** The model's read side: the owner's catalogue and the checklist that survives an unselected turn. */
    @GameTest(maxTicks = 80)
    public void readSideListsTheCatalogueAndTheChecklist(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var owner = player.getUUID();
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var listed = state.sessions().list(owner, 0);
        h.assertFalse(listed.error(), "owner catalogue read must succeed: " + listed.content());
        h.assertTrue(listed.content().contains("builtin:skill-authoring"), "meta-skill is listed");
        h.assertTrue(listed.content().contains("| on |"), "enabled state is reported");
        h.assertTrue(!listed.content().contains("After a goal or a shift ends"), "bodies stay out of the catalogue");
        var help = state.sessions().help();
        h.assertFalse(help.error(), "authoring checklist must be available");
        h.assertTrue(
                help.content().contains("After a goal or a shift ends"), "checklist returns the shipped procedure");
        h.assertTrue(help.content().contains(String.valueOf(SkillLimits.BODY)), "checklist names the real limit");
        h.succeed();
    }

    // Typed dispatch: the handler must map the wire request itself, not a legacy JSON envelope.
    @GameTest(maxTicks = 80)
    public void typedCreateEditDeleteReachTheOwnerBook(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var owner = player.getUUID();
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var book = state.skills().store().book(owner);
        SkillDraft draft = new SkillDraft(
                "Typed procedure",
                "typed description",
                "TYPED_BODY",
                true,
                false,
                Set.of(InstructionSkill.Kind.CHAT),
                List.of("when asked"),
                List.of("typed"),
                true);
        SkillDraft edited = new SkillDraft(
                "Typed procedure",
                "typed description",
                "TYPED_EDITED",
                true,
                false,
                Set.of(InstructionSkill.Kind.CHAT),
                List.of("when asked"),
                List.of("typed"),
                true);
        String[] id = new String[1];
        h.runAfterDelay(2, () -> {
            TestMenuRequests.handle(player, new InstructionRequest.Create(draft, false));
            h.assertTrue(book.snapshot().size() == 1, "typed create must store the draft");
            var saved = book.snapshot().getFirst();
            h.assertTrue(saved.draft().equals(draft), "every submitted draft field survives typed mapping");
            id[0] = saved.id();
            h.runAfterDelay(2, () -> {
                TestMenuRequests.handle(player, new InstructionRequest.Edit(id[0], 1, edited, false));
                var changed = book.find(id[0]).orElseThrow();
                h.assertTrue(changed.draft().equals(edited), "typed edit stores the new draft");
                h.assertTrue(changed.version() == 2, "typed edit advances the record version");
                h.runAfterDelay(2, () -> {
                    TestMenuRequests.handle(player, new InstructionRequest.Delete(id[0], 2, false));
                    h.assertTrue(book.snapshot().isEmpty(), "typed delete removes the record");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = 80)
    public void typedGlobalScopeRequiresOperatorAndLeavesLocalBookUnchanged(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var owner = player.getUUID();
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var local = state.skills()
                .store()
                .book(owner)
                .create(
                        SkillDraft.simple("Local only", "LOCAL_SENTINEL"),
                        InstructionSkill.Source.OWNER,
                        owner,
                        1,
                        false);
        h.runAfterDelay(2, () -> {
            TestMenuRequests.handle(player, new InstructionRequest.Delete(local.id(), 1, true));
            h.assertTrue(
                    state.skills().store().book(owner).find(local.id()).isPresent(),
                    "a nonoperator global delete must not remove the local record");
            h.assertTrue(
                    state.skills().store().global().snapshot().isEmpty(),
                    "a nonoperator global delete must not touch the global book");
            h.runAfterDelay(2, () -> {
                TestMenuRequests.handle(
                        player, new InstructionRequest.Create(SkillDraft.simple("Global", "GLOBAL_SENTINEL"), true));
                h.assertTrue(
                        state.skills().store().global().snapshot().isEmpty(),
                        "a nonoperator global create must be rejected");
                h.assertTrue(
                        state.skills().store().book(owner).snapshot().size() == 1,
                        "a rejected global create must not fall back to the local book");
                h.succeed();
            });
        });
    }

    @GameTest(maxTicks = 80)
    public void typedBuiltinOverrideChangesEffectiveState(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var owner = player.getUUID();
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        String builtin = "builtin:autonomy";
        h.runAfterDelay(2, () -> {
            TestMenuRequests.handle(player, new InstructionRequest.Disable(builtin, 1, false));
            h.assertTrue(
                    !state.skills().actions().enabled(owner, builtin),
                    "typed disable hides the builtin from the effective catalogue");
            h.runAfterDelay(2, () -> {
                TestMenuRequests.handle(player, new InstructionRequest.Enable(builtin, 1, false));
                h.assertTrue(
                        state.skills().actions().enabled(owner, builtin),
                        "typed enable restores the builtin to the effective catalogue");
                h.succeed();
            });
        });
    }

    @GameTest(maxTicks = 60)
    public void cataloguePageAndSelectionSubscriptionsAreIndependent(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        String builtin = "builtin:autonomy";
        TestMenuRequests.handle(player, new InstructionRequest.Open());
        TestMenuRequests.handle(player, new InstructionRequest.Page(1));
        h.assertTrue(
                dev.friendai.server.net.SkillsViewStream.pageOf(player) == 1,
                "page request must move the catalogue to the requested page");
        TestMenuRequests.handle(player, new InstructionRequest.Detail(builtin));
        h.assertTrue(
                dev.friendai.server.net.SkillsViewStream.pageOf(player) == 1, "selection must keep the current page");
        h.assertTrue(
                builtin.equals(dev.friendai.server.net.SkillsViewStream.selectionOf(player)),
                "selection request must select the requested skill");
        TestMenuRequests.handle(player, new InstructionRequest.Page(0));
        h.assertTrue(dev.friendai.server.net.SkillsViewStream.pageOf(player) == 0, "page request must change the page");
        h.assertTrue(
                builtin.equals(dev.friendai.server.net.SkillsViewStream.selectionOf(player)),
                "page request must keep the current selection");
        h.succeed();
    }
}
