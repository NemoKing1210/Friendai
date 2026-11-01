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
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

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

    /**
     * One owner death is said once, and only where it can be heard: beside the companion it raises a single line, while
     * the same death next to a companion that stands far above the owner raises nothing at all.
     */
    /** The journal entry the death hook writes, without needing a body the test harness can actually kill. */
    private static void death(ServerRuntime runtime, ServerPlayer owner, net.minecraft.server.level.ServerLevel level) {
        runtime.memory()
                .book(owner.getUUID())
                .recordHappening(
                        owner.getUUID(),
                        new dev.friendai.features.social.Happening(
                                dev.friendai.features.social.Happening.Kind.DEATH,
                                dev.friendai.features.social.Happening.Importance.HIGH,
                                (int) level.getGameTime(),
                                "death:" + level.getGameTime(),
                                "you died"));
    }

    @GameTest(maxTicks = 600)
    public void anOwnerDeathSpeaksOnceOnlyInEarshot(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        GameTestSupport.platformAt(h, 200, 8);
        var level = h.getLevel();
        ServerRuntime runtime = GameTestSupport.runtime(level.getServer());
        CompanionManager manager = CompanionManager.get(level.getServer());
        int hearing = runtime.config().chat.hearingRadius;

        ServerPlayer nearOwner =
                GameTestSupport.onlinePlayer(h, "social-near", 2, 2, 2, true).player();
        Script nearScript = new Script();
        runtime.sessions().setGatewayOverride(nearOwner.getUUID(), nearScript);
        CompanionEntity nearCompanion = manager.summon(nearOwner);

        ServerPlayer farOwner =
                GameTestSupport.onlinePlayer(h, "social-far", 6, 2, 6, true).player();
        Script farScript = new Script();
        runtime.sessions().setGatewayOverride(farOwner.getUUID(), farScript);
        CompanionEntity farCompanion = manager.summon(farOwner);
        // Earshot is a plain distance, so far above the companion is out of it. Two hundred blocks beat the widest
        // hearingRadius the config allows (64) instead of poking at the shared config other tests read.
        Vec3 highGround = h.absoluteVec(new Vec3(6, 201, 6));
        farOwner.teleportTo(highGround.x, highGround.y, highGround.z);

        h.assertTrue(
                nearOwner.distanceTo(nearCompanion) <= hearing,
                "precondition: the near owner is in earshot, distance " + nearOwner.distanceTo(nearCompanion));
        h.assertTrue(
                farOwner.distanceTo(farCompanion) > hearing,
                "precondition: the far owner is out of earshot, distance " + farOwner.distanceTo(farCompanion));
        // A joined test player rejects lethal damage in this harness, so the death reaches the journal the same way
        // the ServerLivingEntityEvents hook writes it; what this test proves is the director's earshot rule.
        death(runtime, nearOwner, level);
        death(runtime, farOwner, level);

        h.runAfterDelay(300, () -> {
            List<String> near = socialOpenings(nearScript);
            h.assertValueEqual(near.size(), 1, "one death must speak exactly once, openings: " + near);
            h.assertValueEqual(openingsAbout(near, "you died"), 1, "the line must be about the death: " + near);
            List<String> far = socialOpenings(farScript);
            h.assertValueEqual(far.size(), 0, "a death out of earshot must stay silent, openings: " + far);
            h.succeed();
        });
    }

    /**
     * A threat keeps the line back: with a hostile mob holding the companion while the owner dies, nothing is said;
     * when the threat is gone the held death is spoken once.
     */
    @GameTest(maxTicks = 400)
    public void noUrgentLineWhileAZombieIsOnTheOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var level = h.getLevel();
        ServerRuntime runtime = GameTestSupport.runtime(level.getServer());
        ServerPlayer owner =
                GameTestSupport.onlinePlayer(h, "social-threat", 2, 2, 2, true).player();
        Script script = new Script();
        runtime.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionEntity companion = CompanionManager.get(level.getServer()).summon(owner);

        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 3, 2, 2);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        // 26.3 has no setInvulnerable on a mob: enough health keeps the threat standing for the whole window.
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(500.0);
        zombie.setHealth(500.0f);
        boolean[] threatened = {true};
        h.onEachTick(() -> {
            if (!threatened[0] || !zombie.isAlive()) {
                return;
            }
            // The director asks whether the companion is still busy with a threat. Re-point it every tick so the
            // creature standing on the owner keeps holding the line for the whole window, whatever the goals do.
            companion.setTarget(zombie);
        });
        companion.setTarget(zombie);
        h.assertTrue(
                owner.distanceTo(companion) <= runtime.config().chat.hearingRadius,
                "precondition: the owner is in earshot");
        // The harness cannot land lethal damage on a joined player, so seed the journal entry the death hook writes;
        // what this test proves is that a threat holds the line back and the release speaks it once.
        death(runtime, owner, level);

        h.runAfterDelay(120, () -> {
            h.assertTrue(companion.getTarget() != null, "precondition: the threat must still hold the companion");
            h.assertValueEqual(
                    openingsAbout(socialOpenings(script), "you died"),
                    0,
                    "no line while a threat is on the owner, openings: " + socialOpenings(script));
            threatened[0] = false;
            zombie.discard();
            companion.setTarget(null);
            h.assertTrue(
                    owner.distanceTo(companion) <= runtime.config().chat.hearingRadius,
                    "precondition: the owner is back in earshot, distance " + owner.distanceTo(companion));
            h.runAfterDelay(120, () -> {
                h.assertValueEqual(
                        openingsAbout(socialOpenings(script), "you died"),
                        1,
                        "the held line must be spoken once the threat is gone, openings: " + socialOpenings(script));
                h.succeed();
            });
        });
    }

    /**
     * Finishing a task is not news: a short task that succeeds through the coordinator adds no journal happening and
     * raises no line about it. A self-raised subject may still arrive — the director's own opener, not a task report.
     */
    @GameTest(maxTicks = 300)
    public void aNormalTaskFinishProducesNoSocialLine(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        Script script = new Script();
        runtime.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        Set<String> journalBefore = journalKeys(runtime, owner);

        companion.executionCoordinator().start(new WaitTask(20), "wait");
        h.runAfterDelay(10, () -> {
            h.assertTrue(
                    "wait".equals(companion.executionCoordinator().currentToolName()),
                    "precondition: the wait task must be running, current "
                            + companion.executionCoordinator().currentToolName());
            h.runAfterDelay(110, () -> {
                h.assertTrue(
                        !"wait".equals(companion.executionCoordinator().currentToolName()),
                        "precondition: the wait task must have finished");
                h.assertValueEqual(journalKeys(runtime, owner), journalBefore, "a finished task is not a happening");
                List<String> openings = socialOpenings(script);
                for (String opening : openings) {
                    h.assertTrue(
                            opening.contains("Topic:") && !opening.contains("Happenings:"),
                            "a finished task must not be raised as a happening: " + opening);
                }
                h.succeed();
            });
        });
    }

    /**
     * The social openings that actually reached a turn, one entry per posted line. Only the newest message of a
     * request is that turn's event batch; the earlier ones are history and would repeat a line already counted.
     */
    private static List<String> socialOpenings(Script script) {
        List<String> openings = new ArrayList<>();
        synchronized (script.requests) {
            for (List<Message> request : script.requests) {
                if (request.isEmpty()) {
                    continue;
                }
                Message newest = request.get(request.size() - 1);
                for (String line : newest.text().split("\n")) {
                    if (line.startsWith("[event] social")) {
                        openings.add(line);
                    }
                }
            }
        }
        return openings;
    }

    /** How many openings carry the given words, so a line about a death is counted rather than any line at all. */
    private static int openingsAbout(List<String> openings, String words) {
        int count = 0;
        for (String opening : openings) {
            if (opening.contains(words)) {
                count++;
            }
        }
        return count;
    }

    /** The happenings the owner's journal holds, so a test can show that something did not become a fact. */
    private static Set<String> journalKeys(ServerRuntime runtime, ServerPlayer owner) {
        Set<String> keys = new HashSet<>();
        for (var happening :
                runtime.memory().book(owner.getUUID()).journal(owner.getUUID()).recent(12)) {
            keys.add(happening.key());
        }
        return keys;
    }
}
