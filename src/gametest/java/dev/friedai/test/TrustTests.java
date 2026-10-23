package dev.friedai.test;

import com.mojang.authlib.GameProfile;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.ToolResult;
import dev.friedai.tool.builtin.FriedaiTools;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        return FriedaiServerState.get(owner.level().getServer()).brains().runTool(owner.getUUID(), name, json);
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
        h.assertTrue(result.content().startsWith("asked the owner to confirm (request #"),
                "result was " + result.content());
        FriedaiServerState state = FriedaiServerState.get(h.getLevel().getServer());
        h.assertTrue(!state.memory().book(owner.player().getUUID()).isTrusted(target.player().getUUID()),
                "trust must not change before the owner confirms");
        List<Component> messages = drainMessages(owner.channel());
        h.assertTrue(messages.stream().anyMatch(m -> containsKey(m, "friedai.trust.ask.add")),
                "owner got " + messages);
        h.assertTrue(messages.stream().anyMatch(m -> containsKey(m, "friedai.trust.button.confirm")),
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
        h.assertTrue(result.error() && result.content().equals("I don't know a player called " + missing),
                "result was " + result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void trustToolAlreadyTrusted(GameTestHelper h) {
        Mock owner = mockPlayerNamed(h, unique("owner"), 2, 2, 2);
        Mock target = mockPlayerNamed(h, unique("target"), 5, 2, 2);
        summon(owner.player());
        FriedaiServerState state = FriedaiServerState.get(h.getLevel().getServer());
        String targetName = target.player().getName().getString();
        state.memory().book(owner.player().getUUID()).setTrusted(target.player().getUUID(), targetName, true, 0);

        ToolResult result = runTool(owner.player(), "trust_player", "{\"name\":\"" + targetName + "\"}");

        h.assertTrue(!result.error() && result.content().equals(targetName + " is already trusted"),
                "result was " + result.content());
        h.assertTrue(state.trust().current(owner.player().getUUID()).isEmpty(), "no request should open");
        h.succeed();
    }

    @GameTest
    public void trustToolsHiddenFromTrusted(GameTestHelper h) {
        List<String> trusted = FriedaiTools.create(() -> 32).specs(AccessLevel.TRUSTED).stream()
                .map(ToolSpec::name)
                .toList();
        List<String> owner = FriedaiTools.create(() -> 32).specs(AccessLevel.OWNER).stream()
                .map(ToolSpec::name)
                .toList();
        h.assertTrue(!trusted.contains("trust_player") && !trusted.contains("untrust_player"),
                "trusted tools were " + trusted);
        h.assertTrue(owner.contains("trust_player") && owner.contains("untrust_player"),
                "owner tools were " + owner);
        h.succeed();
    }
}
