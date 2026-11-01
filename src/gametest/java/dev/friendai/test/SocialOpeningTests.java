package dev.friendai.test;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The companion may start a line about what happened, with no tools and no invented work. */
public class SocialOpeningTests {
    private static final class Script implements LlmGateway {
        final List<List<Message>> requests = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> toolCounts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new ArrayList<>(messages));
            toolCounts.add(tools.size());
            return CompletableFuture.completedFuture(
                    new LlmResponse("I am back.", List.of(), StopReason.END, new Usage(0, 0)));
        }
    }

    @GameTest(maxTicks = 600)
    public void aJournalEntryReachesTheOwnerAsOneToollessLine(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script();
        runtime.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        runtime.memory()
                .book(owner.getUUID())
                .recordHappening(
                        owner.getUUID(),
                        new dev.friendai.features.social.Happening(
                                dev.friendai.features.social.Happening.Kind.DEATH,
                                dev.friendai.features.social.Happening.Importance.HIGH,
                                0,
                                "death:1",
                                "you died to a creeper"));

        h.succeedWhen(() -> {
            boolean seen = false;
            synchronized (script.requests) {
                for (int i = 0; i < script.requests.size(); i++) {
                    String text = script.requests.get(i).toString();
                    if (text.contains("[event] social") && text.contains("creeper")) {
                        seen = true;
                        h.assertValueEqual(script.toolCounts.get(i), 0, "a social opening must offer no tools");
                    }
                }
            }
            h.assertTrue(seen, "the journal entry should have reached the owner as one line");
        });
    }

    @GameTest(maxTicks = 300)
    public void anAdvancementLandsInTheJournal(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        var holder = runtime.server()
                .getAdvancements()
                .get(net.minecraft.resources.Identifier.withDefaultNamespace("story/mine_stone"));
        h.assertTrue(holder != null, "the root advancement must exist");
        String criterion = holder.value().criteria().keySet().iterator().next();

        boolean awarded = owner.getAdvancements().award(holder, criterion);

        h.assertTrue(awarded, "the advancement should have been awarded");
        h.succeedWhen(() -> {
            boolean recorded =
                    runtime
                            .memory()
                            .book(owner.getUUID())
                            .journal(owner.getUUID())
                            .recent(compatibilityLimit())
                            .stream()
                            .anyMatch(entry -> entry.key().startsWith("advancement:"));
            h.assertTrue(recorded, "an announced advancement must reach the owner's journal");
        });
    }

    private static int compatibilityLimit() {
        return 12;
    }

    @GameTest(maxTicks = 900)
    public void aDeathOpensOneToollessLine(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(new BlockPos(8, 2, 8), Blocks.BED.red().defaultBlockState());
        h.setBlock(
                new BlockPos(8, 2, 7),
                Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        runtime.companionLife()
                .setRespawnPoint(
                        owner.getUUID(),
                        new CompanionLifeData.RespawnPoint(
                                h.getLevel().dimension(), h.absolutePos(new BlockPos(8, 2, 8))));
        Script script = new Script();
        runtime.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);

        companion.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000.0f);

        h.succeedWhen(() -> {
            boolean seen = false;
            synchronized (script.requests) {
                for (int i = 0; i < script.requests.size(); i++) {
                    String text = script.requests.get(i).toString();
                    if (text.contains("[event] social")) {
                        seen = true;
                        h.assertValueEqual(script.toolCounts.get(i), 0, "a social opening must offer no tools");
                    }
                }
            }
            h.assertTrue(seen, "the companion should have said one line about its death");
        });
    }
}
