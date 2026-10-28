package dev.friendai.test;

import com.google.gson.*;
import dev.friendai.agent.llm.model.*;
import dev.friendai.agent.turn.*;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.*;
import dev.friendai.features.instructions.*;
import dev.friendai.infrastructure.persistence.instructions.SkillStore;
import dev.friendai.net.SkillRequestHandler;
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
        var open = new dev.friendai.net.SkillsRequestC2SPayload("open", "{}", 1);
        SkillRequestHandler.handle(player, open);
        SkillRequestHandler.handle(player, new dev.friendai.net.SkillsRequestC2SPayload("close", "{}", 2));
        SkillRequestHandler.handle(player, new dev.friendai.net.SkillsRequestC2SPayload("open", "{}", 3));
        h.assertTrue(
                dev.friendai.net.SkillsViewStream.isOpen(player),
                "switching from catalogue to editor must register the new subscription even in the same tick");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void longImportIsBoundToAuthenticatedSender(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var other = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        JsonObject input = new JsonObject();
        input.addProperty("name", "Long instruction");
        input.addProperty("body", "😀".repeat(2000));
        input.addProperty("owner", other.getUUID().toString());
        input.addProperty("source", "builtin");
        input.addProperty("scope", "server");
        var result = SkillRequestHandler.apply(owner, "import", input.toString());
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
        var state = FriendaiServerState.get(server);
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
                SkillRequestHandler.apply(player, "approve", input.toString())
                        .status()
                        .ok(),
                "owner approval should be accepted");
        var after = state.skills().begin(owner, caller, "hello", TurnKind.PLAYER, "", false, false);
        h.assertTrue(
                after.sections().stream().anyMatch(section -> section.id().equals("skill:" + s.id())),
                "approved procedure reaches the next turn");
        state.skills().store().flushAll();
        var dir = server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skills");
        try (var reloaded = new SkillStore(dir, SkillLimits.defaults(), text -> text)) {
            h.assertTrue(
                    reloaded.book(owner).find(s.id()).orElseThrow().activation() == InstructionSkill.Activation.ACTIVE,
                    "approval survives disk round-trip");
        }
        h.succeed();
    }

    // GameTest ticks are faster than the brain's 500 ms wall-clock turn cooldown.
    @GameTest(maxTicks = 3600)
    public void liveToggleChangesNextGatewayRequest(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        var state = FriendaiServerState.get(server);
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
                        SkillRequestHandler.apply(player, "disable", input.toString())
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
}
