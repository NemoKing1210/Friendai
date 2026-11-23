package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * A contextual order means what the player pointed at when they spoke. The provider is held while the player looks
 * elsewhere, exactly like a slow response, and the companion still mines the first block.
 */
public class ReferentTests {
    private static final float EAST = -90F;
    private static final float SOUTH = 0F;

    /**
     * Answers the first request at once, so the turn ends; the next one is held until the test answers it, and every
     * request after that gets text. That leaves the order waiting behind the turn cooldown, exactly like a provider
     * that is slow to be asked.
     */
    private static final class Script implements LlmGateway {
        final List<String> systems = Collections.synchronizedList(new ArrayList<>());
        volatile CompletableFuture<LlmResponse> held;
        private int calls;

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            systems.add(system);
            if (++calls == 1) {
                return CompletableFuture.completedFuture(
                        new LlmResponse("Hello.", List.of(), StopReason.END, new Usage(0, 0)));
            }
            if (held == null) {
                held = new CompletableFuture<>();
                return held;
            }
            return CompletableFuture.completedFuture(
                    new LlmResponse("On it.", List.of(), StopReason.END, new Usage(0, 0)));
        }
    }

    private static final class Said implements Speaker {
        @Override
        public void say(String text) {}

        @Override
        public void say(UserMessage message) {}

        @Override
        public void thinking(boolean on) {}

        @Override
        public void error(UserMessage message) {}
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 3600)
    public void aDelayedChatOrderMinesTheBlockTheyPointedAt(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(8, 3, 2, Blocks.MANGROVE_LOG);
        h.setBlock(2, 3, 8, Blocks.OAK_LOG);
        ServerPlayer owner =
                GameTestSupport.onlinePlayer(h, "referent-chat", 2, 2, 2, true).player();
        aim(owner, EAST);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script();
        runtime.sessions().setGatewayOverride(owner.getUUID(), script);
        runtime.sessions().setSpeakerWrapper(owner.getUUID(), real -> new Said());

        BlockPos pointed = h.absolutePos(new BlockPos(8, 3, 2));
        BlockPos now = h.absolutePos(new BlockPos(2, 3, 8));

        // The greeting ends a turn, so the order waits out the turn cooldown and its request is built later.
        ChatRouter.onChat(runtime, owner, "Friendai, hello");

        int[] step = {0};
        boolean[] answered = {false};
        h.succeedWhen(() -> {
            if (step[0] == 0) {
                if (script.systems.isEmpty()) {
                    throw new GameTestAssertException(Component.literal("the greeting must reach the provider"), 0);
                }
                aim(owner, EAST);
                ChatRouter.onChat(runtime, owner, "Friendai, break this block");
                // The line is accepted; the player turns to another block before the order's request is built.
                aim(owner, SOUTH);
                step[0] = 1;
                throw new GameTestAssertException(Component.literal("the order is posted"), 0);
            }
            if (script.held == null) {
                throw new GameTestAssertException(Component.literal("waiting for the order's request"), 0);
            }
            if (!answered[0]) {
                String prompt = script.systems.get(script.systems.size() - 1);
                h.assertTrue(
                        prompt.contains("when they spoke: block minecraft:mangrove_log at " + coords(pointed)), prompt);
                h.assertTrue(prompt.contains("looking at now: block oak_log at " + coords(now)), prompt);
                script.held.complete(toolCall(pointed));
                answered[0] = true;
            }
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 3, 2)).isAir(),
                    "the pointed block must be mined; task=" + taskState(companion));
            h.assertTrue(
                    h.getBlockState(new BlockPos(2, 3, 8)).is(Blocks.OAK_LOG),
                    "the block under the new gaze must stay");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 3600)
    public void aDelayedVoiceOrderMinesTheBlockTheyPointedAt(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(8, 3, 2, Blocks.MANGROVE_LOG);
        h.setBlock(2, 3, 8, Blocks.OAK_LOG);
        ServerPlayer owner =
                GameTestSupport.onlinePlayer(h, "referent-voice", 2, 2, 2, true).player();
        aim(owner, EAST);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script();
        runtime.sessions().setGatewayOverride(owner.getUUID(), script);
        runtime.sessions().setSpeakerWrapper(owner.getUUID(), real -> new Said());

        BlockPos pointed = h.absolutePos(new BlockPos(8, 3, 2));
        // The greeting ends a turn, so the spoken order waits out the cooldown and its request is built later.
        ChatRouter.onChat(runtime, owner, "Friendai, hello");

        int[] step = {0};
        boolean[] answered = {false};
        h.succeedWhen(() -> {
            if (step[0] == 0) {
                if (script.systems.isEmpty()) {
                    throw new GameTestAssertException(Component.literal("the greeting must reach the provider"), 0);
                }
                aim(owner, EAST);
                TestMenuRequests.voice(owner, "Friendai, break this block for me");
                aim(owner, SOUTH);
                step[0] = 1;
                throw new GameTestAssertException(Component.literal("the order is posted"), 0);
            }
            if (script.held == null) {
                throw new GameTestAssertException(Component.literal("waiting for the order's request"), 0);
            }
            if (!answered[0]) {
                String prompt = script.systems.get(script.systems.size() - 1);
                h.assertTrue(
                        prompt.contains("when they spoke: block minecraft:mangrove_log at " + coords(pointed)), prompt);
                script.held.complete(toolCall(pointed));
                answered[0] = true;
            }
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 3, 2)).isAir(),
                    "the pointed block must be mined from a spoken order too; task=" + taskState(companion));
            h.assertTrue(
                    h.getBlockState(new BlockPos(2, 3, 8)).is(Blocks.OAK_LOG),
                    "the block under the new gaze must stay");
        });
    }

    private static void aim(ServerPlayer player, float yaw) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0F);
        player.setOnGround(true);
    }

    private static LlmResponse toolCall(BlockPos target) {
        String json = "{\"x\":" + target.getX() + ",\"y\":" + target.getY() + ",\"z\":" + target.getZ()
                + ",\"expect\":\"mangrove_log\",\"vein\":false}";
        return new LlmResponse(
                "",
                List.of(new ToolCall(
                        "call_1", "mine_block_at", JsonParser.parseString(json).getAsJsonObject(), json, null)),
                StopReason.TOOL_USE,
                new Usage(0, 0));
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String taskState(CompanionEntity companion) {
        return companion
                .executionCoordinator()
                .current()
                .map(task -> task.type() + ":" + task.resultSummary())
                .orElse("none");
    }
}
