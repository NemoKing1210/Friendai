package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.brain.BrainEvent;
import dev.friendai.brain.LlmGateway;
import dev.friendai.brain.Speaker;
import dev.friendai.chat.ChatRouter;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.Role;
import dev.friendai.llm.model.StopReason;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.llm.model.Usage;
import dev.friendai.text.UserMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

public class BrainServiceTests {
    /** Answers from a queue and records every request. A null entry means "never answer". */
    private static final class Script implements LlmGateway {
        final Queue<LlmResponse> responses = new ArrayDeque<>();
        final List<List<Message>> requests = Collections.synchronizedList(new ArrayList<>());
        boolean hang;

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new ArrayList<>(messages));
            if (hang) {
                return new CompletableFuture<>();
            }
            LlmResponse next = responses.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("script exhausted"))
                    : CompletableFuture.completedFuture(next);
        }

        Script text(String text) {
            responses.add(new LlmResponse(text, List.of(), StopReason.END, new Usage(0, 0)));
            return this;
        }

        Script call(String name, String json) {
            JsonObject args = JsonParser.parseString(json).getAsJsonObject();
            responses.add(new LlmResponse(
                    "", List.of(new ToolCall("call_1", name, args, json, null)), StopReason.TOOL_USE, new Usage(0, 0)));
            return this;
        }
    }

    private static final class Said implements Speaker {
        final List<String> said = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public void thinking(boolean on) {}

        @Override
        public void error(UserMessage message) {}
    }

    @GameTest(maxTicks = 100)
    public void chatAddressedMessageReachesBrain(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        Script script = new Script().text("Hi");
        Said said = new Said();
        brains.setGatewayOverride(player.getUUID(), script);
        brains.setSpeakerWrapper(player.getUUID(), real -> said);
        CompanionManager.get(server).summon(player);
        ChatRouter.onChat(player, "Friendai, hi");
        h.succeedWhen(() -> h.assertTrue(said.said.contains("Hi"), "said " + said.said));
    }

    // The brain waits brain.cooldownMs (500 ms of real time) between turns, while a gametest tick is far shorter
    // than 50 ms: 900 ticks is only just over two cooldowns, so leave a wide budget.
    @GameTest(maxTicks = 3600)
    public void toolCallStartsTaskAndFinishEventReturns(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos log = new BlockPos(4, 2, 2);
        h.setBlock(log, Blocks.MANGROVE_LOG);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        Script script = new Script()
                .call("mine_blocks", "{\"block\":\"minecraft:mangrove_log\",\"count\":1}")
                .text("On it")
                .text("Done");
        brains.setGatewayOverride(player.getUUID(), script);
        CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.PlayerSaid(player.getUUID(), "Steve", "mine a log", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 3, "requests so far: " + script.requests.size());
            List<Message> third = script.requests.get(2);
            Message last = third.get(third.size() - 1);
            h.assertTrue(last.role() == Role.USER && last.text().contains("[event] task #1 mine_blocks finished"),
                    "last message was " + last.text());
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(log)).isAir(), "log should be mined");
        });
    }

    @GameTest(maxTicks = 3600)
    public void noticeReachesBrain(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        Script script = new Script().text("Hi").text("Noted");
        brains.setGatewayOverride(player.getUUID(), script);
        CompanionEntity companion = CompanionManager.get(server).summon(player);
        brains.sendMessage(player.getUUID(), "Steve", "hello");
        companion.taskRunner().notice("x");
        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 2, "requests so far: " + script.requests.size());
            boolean seen = script.requests.stream().anyMatch(request -> {
                Message last = request.get(request.size() - 1);
                return last.role() == Role.USER && last.text().contains("[event] x");
            });
            h.assertTrue(seen, "no request carried the world notice");
        });
    }

    @GameTest(maxTicks = 100)
    public void disconnectCancelsBrain(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        Script script = new Script();
        script.hang = true;
        brains.setGatewayOverride(player.getUUID(), script);
        CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.PlayerSaid(player.getUUID(), "Steve", "hello", BrainEvent.Source.CHAT));
        h.assertTrue(brains.brainOf(player.getUUID()).get().isThinking(), "brain should be waiting for the LLM");
        brains.onOwnerLeft(player.getUUID());
        h.assertTrue(!brains.brainOf(player.getUUID()).get().isThinking(), "brain should have been cancelled");
        h.succeed();
    }

    @GameTest
    public void reloadSwapsProvider(GameTestHelper h) {
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        ServerConfig original = state.config();
        ServerConfig changed = ServerConfig.defaults();
        changed.profiles.get(changed.activeProfile).model = "swapped-model";
        state.applyConfig(changed);
        h.assertTrue("swapped-model".equals(state.brains().activeModel()), "model was " + state.brains().activeModel());
        state.applyConfig(original);
        h.assertTrue(!"swapped-model".equals(state.brains().activeModel()), "original config should be restored");
        h.succeed();
    }
}
