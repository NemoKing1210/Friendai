package dev.friendai.test;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.conversation.Audience;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.agent.chat.CompanionRouting;
import dev.friendai.server.agent.chat.CompanionSpeaker;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** The player's input channel decides who receives the companion's reply. */
public class AudienceTests {
    private static final class Script implements LlmGateway {
        private final Queue<String> replies = new ArrayDeque<>();

        Script reply(String text) {
            replies.add(text);
            return this;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            String next = replies.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("script exhausted"))
                    : CompletableFuture.completedFuture(
                            new LlmResponse(next, List.of(), StopReason.END, new Usage(0, 0)));
        }
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void privateVoiceOnlyReachesTheSpeaker(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        GameTestSupport.OnlinePlayer owner = GameTestSupport.onlinePlayer(h, "audience-owner", 2, 2, 2, true);
        GameTestSupport.OnlinePlayer guest = GameTestSupport.onlinePlayer(h, "audience-guest", 3, 2, 3, true);
        GameTestSupport.OnlinePlayer distant = GameTestSupport.onlinePlayer(h, "audience-distant", 4, 2, 4, true);
        Vec3 highGround = h.absoluteVec(new Vec3(4, 202, 4));
        distant.player().teleportTo(highGround.x, highGround.y, highGround.z);

        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script().reply("Only for you.");
        runtime.sessions().setGatewayOverride(owner.id(), script);
        CompanionManager.get(h.getLevel().getServer()).summon(owner.player());
        drain(owner, new ArrayList<>());
        drain(guest, new ArrayList<>());
        drain(distant, new ArrayList<>());

        TestMenuRequests.voice(owner.player(), "hello");

        List<String> ownerSeen = new ArrayList<>();
        List<String> guestSeen = new ArrayList<>();
        List<String> distantSeen = new ArrayList<>();
        h.succeedWhen(() -> {
            drain(owner, ownerSeen);
            drain(guest, guestSeen);
            drain(distant, distantSeen);
            h.assertTrue(ownerSeen.stream().anyMatch(line -> line.contains("Only for you.")), ownerSeen.toString());
            // Other parallel tests may write their own public chat into these channels; only this test's reply is
            // evidence for the private channel.
            h.assertTrue(
                    guestSeen.stream().noneMatch(line -> line.contains("Only for you.")),
                    "a private reply reached a nearby guest: " + guestSeen);
            h.assertTrue(
                    distantSeen.stream().noneMatch(line -> line.contains("Only for you.")),
                    "a private reply reached a distant guest: " + distantSeen);
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void typedChatReachesEveryPlayer(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        GameTestSupport.OnlinePlayer owner = GameTestSupport.onlinePlayer(h, "audience-owner", 2, 2, 2, true);
        GameTestSupport.OnlinePlayer guest = GameTestSupport.onlinePlayer(h, "audience-guest", 3, 2, 3, true);
        GameTestSupport.OnlinePlayer distant = GameTestSupport.onlinePlayer(h, "audience-distant", 4, 2, 4, true);
        Vec3 highGround = h.absoluteVec(new Vec3(4, 202, 4));
        distant.player().teleportTo(highGround.x, highGround.y, highGround.z);

        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script().reply("Hello, everyone.");
        runtime.sessions().setGatewayOverride(owner.id(), script);
        CompanionManager.get(h.getLevel().getServer()).summon(owner.player());
        drain(owner, new ArrayList<>());
        drain(guest, new ArrayList<>());
        drain(distant, new ArrayList<>());

        ChatRouter.onChat(runtime, owner.player(), "@ hello");

        List<String> ownerSeen = new ArrayList<>();
        List<String> guestSeen = new ArrayList<>();
        List<String> distantSeen = new ArrayList<>();
        h.succeedWhen(() -> {
            drain(owner, ownerSeen);
            drain(guest, guestSeen);
            drain(distant, distantSeen);
            h.assertTrue(ownerSeen.stream().anyMatch(line -> line.contains("Hello, everyone.")), ownerSeen.toString());
            h.assertTrue(guestSeen.stream().anyMatch(line -> line.contains("Hello, everyone.")), guestSeen.toString());
            h.assertTrue(
                    distantSeen.stream().anyMatch(line -> line.contains("Hello, everyone.")),
                    "public chat must reach a player outside hearing: " + distantSeen);
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 600)
    public void guestActivityDoesNotMoveTheOwnerCount(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        GameTestSupport.OnlinePlayer owner = GameTestSupport.onlinePlayer(h, "audience-owner", 2, 2, 2, true);
        GameTestSupport.OnlinePlayer guest = GameTestSupport.onlinePlayer(h, "audience-guest", 3, 2, 3, true);

        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script().reply("For the guest.").reply("For the owner.");
        runtime.sessions().setGatewayOverride(owner.id(), script);
        CompanionManager.get(h.getLevel().getServer()).summon(owner.player());
        drain(owner, new ArrayList<>());
        drain(guest, new ArrayList<>());

        // The pick is proven by its own unit tests; here the guest line goes straight to the companion whose
        // activity must count it, so a parallel test's companion can never take the line.
        CompanionRouting.deliver(runtime, guest.player(), owner.id(), "hi", BrainEvent.Source.CHAT);

        List<String> ownerSeen = new ArrayList<>();
        List<String> guestSeen = new ArrayList<>();
        boolean[] ownerAsked = {false};
        h.succeedWhen(() -> {
            drain(owner, ownerSeen);
            drain(guest, guestSeen);
            if (!ownerAsked[0]) {
                h.assertTrue(
                        guestSeen.stream().anyMatch(line -> line.contains("For the guest.")),
                        "waiting for the guest reply: " + guestSeen);
                ownerAsked[0] = true;
                ChatRouter.onChat(runtime, owner.player(), "@ hello");
            }
            h.assertTrue(
                    ownerSeen.stream().anyMatch(line -> line.contains("For the owner.")),
                    "waiting for the owner reply: " + ownerSeen);
            String social = runtime.socialDirector().activityOf(owner.id()).summary();
            h.assertTrue(social.contains("1 owner messages, 1 guest messages"), social);
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
    public void aComposedLineKeepsChatPublicButHonoursPrivate(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        GameTestSupport.OnlinePlayer owner = GameTestSupport.onlinePlayer(h, "audience-owner", 2, 2, 2, true);
        // A player outside the companion's voice range is the one that separates a chat line from its spoken form.
        GameTestSupport.OnlinePlayer distant = GameTestSupport.onlinePlayer(h, "audience-distant", 4, 2, 4, true);
        Vec3 highGround = h.absoluteVec(new Vec3(4, 202, 4));
        distant.player().teleportTo(highGround.x, highGround.y, highGround.z);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionManager.get(h.getLevel().getServer()).summon(owner.player());
        UUID companionId = runtime.companions().find(owner.id()).orElseThrow().getUUID();
        String name = "Audience-" + UUID.randomUUID().toString().substring(0, 8);
        Speaker speaker = new CompanionSpeaker(
                h.getLevel().getServer(),
                owner.id(),
                () -> name,
                () -> 400,
                thinking -> {},
                () -> companionId,
                () -> 16);

        speaker.say(UserMessage.of("friendai.agent.plan_queued"), Audience.NEARBY, owner.id());
        speaker.say(UserMessage.of("friendai.agent.plan_queued"), Audience.PRIVATE, owner.id());

        List<String> ownerSeen = new ArrayList<>();
        List<String> distantSeen = new ArrayList<>();
        drain(owner, ownerSeen);
        drain(distant, distantSeen);
        // A companion-initiated line is ordinary chat even when its voice carries only nearby, while a private
        // turn's line never reaches another screen.
        h.assertValueEqual(
                2L,
                ownerSeen.stream()
                        .filter(line -> line.startsWith("<" + name + ">"))
                        .count(),
                ownerSeen.toString());
        h.assertValueEqual(
                1L,
                distantSeen.stream()
                        .filter(line -> line.startsWith("<" + name + ">"))
                        .count(),
                distantSeen.toString());
        h.succeed();
    }

    private static void drain(GameTestSupport.OnlinePlayer player, List<String> into) {
        player.channel().flushOutbound();
        for (Component message : player.drainSystemMessages()) {
            into.add(message.getString());
        }
    }
}
