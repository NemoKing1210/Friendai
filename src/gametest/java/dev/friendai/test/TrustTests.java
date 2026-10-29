package dev.friendai.test;

import com.mojang.authlib.GameProfile;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.core.TrustConfirmations;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/** Trust changes asked for in chat or voice: owner-only tools, confirmation outside the model. */
public class TrustTests {
    /** A mock player whose connection stays open, so system messages can be read back. */
    record Mock(ServerPlayer player, EmbeddedChannel channel) {}

    /** Answers every brain request with text and records the last message of each request. */
    private static final class Recorder implements LlmGateway {
        final List<List<Message>> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new ArrayList<>(messages));
            return CompletableFuture.completedFuture(new LlmResponse("ok", List.of(), StopReason.END, new Usage(0, 0)));
        }

        boolean contains(String text) {
            synchronized (requests) {
                return requests.stream()
                        .anyMatch(request -> !request.isEmpty()
                                && request.get(request.size() - 1).text().contains(text));
            }
        }
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("removal")
    private static Mock mockPlayerNamed(GameTestHelper h, String name, double x, double y, double z) {
        var server = h.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(x, y, z));
        player.teleportTo(pos.x, pos.y, pos.z);
        return new Mock(player, channel);
    }

    private static List<Component> drainMessages(EmbeddedChannel channel) {
        List<Component> messages = new ArrayList<>();
        Object packet;
        while ((packet = channel.outboundMessages().poll()) != null) {
            if (packet instanceof ClientboundSystemChatPacket chat) {
                messages.add(chat.content());
            }
        }
        return messages;
    }

    private static boolean containsKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(key)) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsKey(sibling, key)) {
                return true;
            }
        }
        return false;
    }

    private static CompanionEntity summon(ServerPlayer owner) {
        return CompanionManager.get(owner.level().getServer()).summon(owner);
    }

    private static ToolResult runTool(ServerPlayer owner, String name, String json) {
        return FriendaiServerState.get(owner.level().getServer()).sessions().runTool(owner.getUUID(), name, json);
    }

    @GameTest(maxTicks = 100)
    public void trustToolOpensRequest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        drainMessages(owner.channel());

        String targetName = target.player().getName().getString();
        ToolResult result = runTool(owner.player(), "trust_player", "{\"name\":\"" + targetName + "\"}");

        h.assertTrue(!result.error(), "result was " + result.content());
        h.assertTrue(
                result.content().startsWith("asked the owner to confirm (request #"), "result was " + result.content());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        h.assertTrue(
                !state.memory()
                        .book(owner.player().getUUID())
                        .isTrusted(target.player().getUUID()),
                "trust must not change before the owner confirms");
        List<Component> messages = drainMessages(owner.channel());
        h.assertTrue(
                messages.stream().anyMatch(m -> containsKey(m, "friendai.trust.ask.add")), "owner got " + messages);
        h.assertTrue(
                messages.stream().anyMatch(m -> containsKey(m, "friendai.trust.button.confirm")),
                "owner got " + messages);
        h.assertTrue(state.trust().current(owner.player().getUUID()).isPresent(), "a request should be open");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void trustToolUnknownName(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        summon(owner.player());
        String missing = unique("Zed");
        ToolResult result = runTool(owner.player(), "trust_player", "{\"name\":\"" + missing + "\"}");
        h.assertTrue(
                result.error() && result.content().equals("I don't know a player called " + missing),
                "result was " + result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void trustToolAlreadyTrusted(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        String targetName = target.player().getName().getString();
        state.memory().book(owner.player().getUUID()).setTrusted(target.player().getUUID(), targetName, true, 0);

        ToolResult result = runTool(owner.player(), "trust_player", "{\"name\":\"" + targetName + "\"}");

        h.assertTrue(
                !result.error() && result.content().equals(targetName + " is already trusted"),
                "result was " + result.content());
        h.assertTrue(state.trust().current(owner.player().getUUID()).isEmpty(), "no request should open");
        h.succeed();
    }

    @GameTest
    public void trustToolsHiddenFromTrusted(GameTestHelper h) {
        List<String> trusted = FriendaiTools.create(() -> 32).specs(AccessLevel.TRUSTED).stream()
                .map(ToolSpec::name)
                .toList();
        List<String> owner = FriendaiTools.create(() -> 32).specs(AccessLevel.OWNER).stream()
                .map(ToolSpec::name)
                .toList();
        h.assertTrue(
                !trusted.contains("trust_player") && !trusted.contains("untrust_player"),
                "trusted tools were " + trusted);
        h.assertTrue(owner.contains("trust_player") && owner.contains("untrust_player"), "owner tools were " + owner);
        h.succeed();
    }

    private static Recorder gateway(GameTestHelper h, ServerPlayer owner) {
        Recorder recorder = new Recorder();
        FriendaiServerState.get(h.getLevel().getServer()).sessions().setGatewayOverride(owner.getUUID(), recorder);
        return recorder;
    }

    /** A test-private confirmation queue, so a synthetic future tick cannot expire another test's request. */
    private static TrustConfirmations isolated(GameTestHelper h) {
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        return new TrustConfirmations(h.getLevel().getServer(), state.memory(), state.sessions(), state::config);
    }

    @GameTest(maxTicks = 3600)
    public void yesConfirms(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        Recorder recorder = gateway(h, owner.player());
        String name = target.player().getName().getString();
        state.trust().request(owner.player(), target.player().getUUID(), name, true);

        h.assertTrue(state.trust().answer(owner.player(), "да"), "да should confirm");
        h.assertTrue(
                state.memory()
                        .book(owner.player().getUUID())
                        .isTrusted(target.player().getUUID()),
                "the target should be trusted");
        h.succeedWhen(() -> h.assertTrue(
                recorder.contains("[event] the owner confirmed: " + name + " is now trusted"),
                "requests were " + recorder.requests));
    }

    @GameTest(maxTicks = 3600)
    public void noCancels(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        Recorder recorder = gateway(h, owner.player());
        String name = target.player().getName().getString();
        state.trust().request(owner.player(), target.player().getUUID(), name, true);

        h.assertTrue(state.trust().answer(owner.player(), "нет"), "нет should cancel");
        h.assertTrue(
                !state.memory()
                        .book(owner.player().getUUID())
                        .isTrusted(target.player().getUUID()),
                "the target must not become trusted");
        h.assertTrue(state.trust().current(owner.player().getUUID()).isEmpty(), "the request is gone");
        h.succeedWhen(() -> h.assertTrue(
                recorder.contains("[event] the owner cancelled the trust change for " + name),
                "requests were " + recorder.requests));
    }

    @GameTest(maxTicks = 100)
    public void sentenceDoesNotConfirm(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        state.trust()
                .request(
                        owner.player(),
                        target.player().getUUID(),
                        target.player().getName().getString(),
                        true);

        h.assertTrue(!state.trust().answer(owner.player(), "да, и принеси дерево"), "a sentence is not a confirmation");
        h.assertTrue(state.trust().current(owner.player().getUUID()).isPresent(), "the request stays open");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void otherPlayersAnswerIgnored(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        Mock other = mockPlayerNamed(h, unique("other"), 4, 2, 4);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        state.trust()
                .request(
                        owner.player(),
                        target.player().getUUID(),
                        target.player().getName().getString(),
                        true);

        h.assertTrue(!state.trust().answer(other.player(), "yes"), "another player's yes must be ignored");
        h.assertTrue(state.trust().current(owner.player().getUUID()).isPresent(), "the owner's request stays open");
        h.succeed();
    }

    @GameTest(maxTicks = 3600)
    public void yesWithoutRequestRoutes(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        Recorder recorder = gateway(h, owner.player());

        h.assertTrue(!state.trust().answer(owner.player(), "да"), "nothing to confirm");
        ChatRouter.onChat(ServerRuntimeRegistry.getOrCreate(h.getLevel().getServer()), owner.player(), "Friendai, да");
        h.succeedWhen(() -> h.assertTrue(recorder.contains("]: да"), "requests were " + recorder.requests));
    }

    @GameTest(maxTicks = 100)
    public void staleCommandFails(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        summon(owner.player());
        drainMessages(owner.channel());

        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        int result;
        try {
            result = dispatcher.execute(dispatcher.parse(
                    "friendai pending confirm 999", owner.player().createCommandSourceStack()));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }

        h.assertTrue(result == 0, "result was " + result);
        List<Component> messages = drainMessages(owner.channel());
        h.assertTrue(messages.stream().anyMatch(m -> containsKey(m, "friendai.trust.none")), "owner got " + messages);
        h.succeed();
    }

    @GameTest(maxTicks = 3600)
    public void confirmAfterTargetLeft(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        gateway(h, owner.player());
        UUID targetId = target.player().getUUID();
        String name = target.player().getName().getString();
        state.trust().request(owner.player(), targetId, name, true);

        h.getLevel().getServer().getPlayerList().remove(target.player());
        h.assertTrue(state.trust().answer(owner.player(), "yes"), "yes should still confirm");
        h.assertTrue(
                state.memory().book(owner.player().getUUID()).isTrusted(targetId),
                "trust is applied by UUID even after the target left");
        h.succeed();
    }

    @GameTest(maxTicks = 3600)
    public void untrustFlow(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        gateway(h, owner.player());
        String name = target.player().getName().getString();
        state.memory().book(owner.player().getUUID()).setTrusted(target.player().getUUID(), name, true, 0);

        ToolResult result = runTool(owner.player(), "untrust_player", "{\"name\":\"" + name + "\"}");
        h.assertTrue(
                !result.error() && result.content().startsWith("asked the owner to confirm (request #"),
                "result was " + result.content());
        h.assertTrue(state.trust().answer(owner.player(), "yes"), "yes should confirm");
        h.assertTrue(
                !state.memory()
                        .book(owner.player().getUUID())
                        .isTrusted(target.player().getUUID()),
                "the target should no longer be trusted");
        h.succeed();
    }

    @GameTest(maxTicks = 3600)
    public void expiryNotifies(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        Recorder recorder = gateway(h, owner.player());
        String name = target.player().getName().getString();
        TrustConfirmations confirmations = isolated(h);
        confirmations.request(owner.player(), target.player().getUUID(), name, true);
        drainMessages(owner.channel());

        confirmations.tick(System.currentTimeMillis() + 61_000);

        h.assertTrue(confirmations.current(owner.player().getUUID()).isEmpty(), "the request should be gone");
        List<Component> messages = drainMessages(owner.channel());
        h.assertTrue(
                messages.stream().anyMatch(m -> containsKey(m, "friendai.trust.expired")), "owner got " + messages);
        h.succeedWhen(() -> h.assertTrue(
                recorder.contains("[event] the trust request for " + name + " expired"),
                "requests were " + recorder.requests));
    }

    @GameTest(maxTicks = 100)
    public void expiryWhileOwnerOfflineDoesNotWakeBrain(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        Recorder recorder = gateway(h, owner.player());
        TrustConfirmations confirmations = isolated(h);
        confirmations.request(
                owner.player(),
                target.player().getUUID(),
                target.player().getName().getString(),
                true);
        h.getLevel().getServer().getPlayerList().remove(owner.player());

        confirmations.tick(System.currentTimeMillis() + 61_000);

        h.runAfterDelay(20, () -> {
            h.assertTrue(
                    recorder.requests.isEmpty(),
                    "an offline owner's expiry must not wake the brain: " + recorder.requests);
            h.succeed();
        });
    }
}
