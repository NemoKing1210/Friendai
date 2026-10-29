package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.combat.ProtectionPolicy;
import dev.friendai.features.cooking.minecraft.FurnaceJob;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.Subject;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.features.navigation.movement.PaceMode;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.features.survival.DeathRecord;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.agent.CompanionPerception;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

/** Death keeps what the companion carries, resumes its work and leaves one prompt line behind. */
public class CompanionDeathTests {
    private static final int BED_X = 8;
    private static final int BED_Y = 2;
    private static final int BED_Z = 8;

    /** Answers with one fixed text and records the requests; used to prove the brain still answers after death. */
    private static final class Script implements LlmGateway {
        final List<List<Message>> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new ArrayList<>(messages));
            return CompletableFuture.completedFuture(
                    new LlmResponse("Still here", List.of(), StopReason.END, new Usage(0, 0)));
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

    /** A floor with a valid bed at the far edge, so the manager has somewhere to respawn inside the test area. */
    private static ServerPlayer ready(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(new BlockPos(BED_X, BED_Y, BED_Z), Blocks.BED.red().defaultBlockState());
        h.setBlock(
                new BlockPos(BED_X, BED_Y, BED_Z - 1),
                Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        FriendaiServerState.get(h.getLevel().getServer())
                .companionLife()
                .setRespawnPoint(
                        owner.getUUID(),
                        new CompanionLifeData.RespawnPoint(
                                h.getLevel().dimension(), h.absolutePos(new BlockPos(BED_X, BED_Y, BED_Z))));
        return owner;
    }

    private static void kill(CompanionEntity companion, GameTestHelper h) {
        companion.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000.0f);
    }

    private static Optional<CompanionEntity> replacement(CompanionManager manager, UUID owner, UUID dead) {
        return manager.find(owner).filter(entity -> !entity.getUUID().equals(dead));
    }

    @GameTest(maxTicks = 200)
    public void deathKeepsInventoryEquipmentAndVitals(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        byte[] skin = {1, 2, 3};
        state.skins().save(owner.getUUID(), skin, true);
        state.companionLife().setCreativeMode(owner.getUUID(), true);
        long leaseKey = 42L;
        companion
                .stationLease()
                .keep(Station.CRAFTING_TABLE, leaseKey, h.getLevel().getGameTime());
        BlockPos furnacePos = h.absolutePos(new BlockPos(10, BED_Y, 10));
        h.setBlock(new BlockPos(10, BED_Y, 10), Blocks.FURNACE);
        companion.furnaceJobs().add(new FurnaceJob(furnacePos, Station.FURNACE, "iron_ore", "iron_ingot", 0, false));
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 5));
        companion.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        companion.hunger().load(7, 2.5f, 1.0f);
        companion.addExperience(25);
        companion.setCombatSettings(companion
                .combatSettings()
                .withProtection(ProtectionPolicy.SELF_ONLY)
                .withPursuitLimit(12));
        companion.setPaceMode(PaceMode.RUN);
        AABB spot = companion.getBoundingBox().inflate(6.0);
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            CompanionEntity revived = back.get();
            h.assertTrue(revived.inventory().countItem(Items.OAK_LOG) == 5, "the logs must survive death");
            h.assertTrue(
                    revived.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS), "the boots must survive death");
            h.assertValueEqual(revived.hunger().food(), 7, "hunger must survive death");
            h.assertValueEqual(revived.experience().points(), 25, "experience must survive death");
            h.assertValueEqual(
                    revived.combatSettings().protection(),
                    ProtectionPolicy.SELF_ONLY,
                    "combat settings must survive death");
            h.assertValueEqual(revived.paceMode(), PaceMode.RUN, "pace must survive death");
            h.assertValueEqual(revived.getSkinHash(), SkinHash.of(skin), "the owner's skin must survive death");
            h.assertTrue(revived.isSlimSkin(), "the slim arm model must survive death");
            h.assertTrue(revived.creativeMode(), "the creative preference must survive death");
            h.assertValueEqual(
                    revived.stationLease()
                            .held(Station.CRAFTING_TABLE, h.getLevel().getGameTime())
                            .orElse(-1L),
                    leaseKey,
                    "the station lease must survive death");
            h.assertTrue(
                    revived.furnaceJobs().all().stream()
                            .anyMatch(job -> job.pos().equals(furnacePos)),
                    "the loaded furnace must survive death");
            h.assertTrue(
                    h.getLevel().getEntitiesOfClass(ItemEntity.class, spot).stream()
                            .noneMatch(item -> item.getItem().is(Items.OAK_LOG)
                                    || item.getItem().is(Items.IRON_BOOTS)),
                    "death must not scatter the carried items");

            TagValueOutput out = TagValueOutput.createWithContext(
                    ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            revived.saveWithoutId(out);
            CompanionEntity copy = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
            copy.load(TagValueInput.create(
                    ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            h.assertTrue(
                    copy.inventory().countItem(Items.OAK_LOG) == 5, "a world reload must keep the restored inventory");
            h.assertTrue(
                    copy.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS),
                    "a world reload must keep the restored equipment");
        });
    }

    @GameTest(maxTicks = 100)
    public void aSecondDeathCallIsNotRecordedTwice(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);

        companion.die(h.getLevel().damageSources().genericKill());
        companion.die(h.getLevel().damageSources().genericKill());

        h.assertValueEqual(
                CompanionCarryData.get(h.getLevel().getServer()).deathCount(owner.getUUID()),
                1,
                "a repeated death path must not append a second record");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void deathResumesAStandaloneTask(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion.executionCoordinator().start(new StayTask(), "stay");
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            h.assertTrue(
                    back.get().executionCoordinator().current().isPresent(), "the running task should be restored");
            h.assertValueEqual(
                    back.get().executionCoordinator().current().orElseThrow().type(),
                    "stay",
                    "the restored task should be the one that was running");
        });
    }

    @GameTest(maxTicks = 200)
    public void deathKeepsTheQueue(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion
                .executionCoordinator()
                .queue()
                .addDirect(new QueuedStep(
                        "stay", new JsonObject(), null, List.of(), null, null, 1, List.of(), owner.getUUID(), false));
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            h.assertTrue(
                    back.get().executionCoordinator().queue().jobCount() > 0, "the queued work should be restored");
            h.assertTrue(
                    back.get().executionCoordinator().queue().jobs().stream()
                            .noneMatch(
                                    job -> dev.friendai.execution.queue.Job.MISSING_TOOL_RESULT.equals(job.result())),
                    "the restored job must still name a known tool");
        });
    }

    @GameTest(maxTicks = 200)
    public void summonAfterDeathRestoresWhenAutoRespawnIsOff(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        state.companionLife().setAutoRespawn(owner.getUUID(), false);
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 4));
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.runAfterDelay(60, () -> {
            h.assertTrue(
                    replacement(manager, owner.getUUID(), dead).isEmpty(),
                    "auto-respawn is off, so no replacement may appear");
            h.assertTrue(manager.find(owner.getUUID()).isEmpty(), "the dead body should be removed by now");
            CompanionEntity summoned = manager.summon(owner);
            h.assertTrue(!summoned.getUUID().equals(dead), "summoning after a death must create a new body");
            h.assertTrue(
                    summoned.inventory().countItem(Items.OAK_LOG) == 4,
                    "a summon after a death must restore the carried state");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void deathKeepsPerOwnerStores(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        UUID ownerId = owner.getUUID();
        long tick = h.getLevel().getGameTime();
        state.memory()
                .book(ownerId)
                .addFact(
                        Subject.player(ownerId),
                        Category.NOTE,
                        "the owner likes oak",
                        ownerId,
                        "Owner",
                        System.currentTimeMillis(),
                        MemoryLimits.DEFAULTS);
        var profile = state.profiles().profile(ownerId);
        state.profiles()
                .save(
                        ownerId,
                        new dev.friendai.features.memory.CompanionProfile(
                                "Mira", profile.description(), profile.ownerProfile()));
        state.goals().board(ownerId).set("gather oak", List.of(), false, tick);
        state.places()
                .book(ownerId)
                .remember(
                        new PlaceValues(
                                "oak camp",
                                PlaceKind.HOUSE,
                                "house",
                                h.getLevel().dimension().identifier().getPath(),
                                Pos.of(3, 2, 3),
                                4,
                                Set.of(),
                                PlaceSource.MANUAL,
                                ownerId,
                                ""),
                        tick);
        state.freeTime().start(ownerId, FreeTime.Origin.IDLE, "", System.currentTimeMillis());
        state.companionLife().setFollowMode(ownerId, true);
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, ownerId, dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            h.assertTrue(
                    state.memory().book(ownerId).allFacts().stream()
                            .anyMatch(fact -> fact.text().equals("the owner likes oak")),
                    "the memory fact must survive");
            h.assertValueEqual(state.profiles().profile(ownerId).name(), "Mira", "the name");
            h.assertValueEqual(
                    back.get().getCustomName() == null
                            ? ""
                            : back.get().getCustomName().getString(),
                    "Mira",
                    "the name tag must be applied to the new body");
            h.assertTrue(state.goals().board(ownerId).primary().isPresent(), "the owner's goal must survive");
            h.assertTrue(
                    state.places()
                            .book(ownerId)
                            .find(
                                    "oak camp",
                                    h.getLevel().dimension().identifier().getPath())
                            .isPresent(),
                    "the remembered place must survive");
            h.assertTrue(state.freeTime().state(ownerId).on(), "free time must survive");
            h.assertTrue(
                    state.companionLife().followModeChoice(ownerId).orElse(false),
                    "the follow preference must survive");
        });
    }

    @GameTest(maxTicks = 200)
    public void deathKeepsAFreeTimeShiftPlan(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        UUID ownerId = owner.getUUID();
        long now = System.currentTimeMillis();
        var added = companion
                .executionCoordinator()
                .queue()
                .addPlan(
                        "free time: gather wood",
                        List.of(new QueuedStep(
                                "jump", new JsonObject(), null, List.of(), null, null, 1, List.of(), ownerId, false)),
                        PlanOptions.DEFAULT);
        state.freeTime().start(ownerId, FreeTime.Origin.IDLE, "", now);
        state.freeTime().beginShift(ownerId, "gather wood", List.of(), "", NeedKind.FARM, false, now, now + 600_000L);
        state.freeTime().linkPlan(ownerId, added.planId());
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, ownerId, dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            h.assertTrue(
                    back.get()
                            .executionCoordinator()
                            .queue()
                            .plan(added.planId())
                            .isPresent(),
                    "the shift's plan must be restored with its id");
            var shift = state.freeTime().state(ownerId).shift();
            h.assertTrue(
                    shift != null,
                    "the free-time shift must stay open, history: "
                            + state.freeTime().state(ownerId).history());
            h.assertValueEqual(shift.planId(), added.planId(), "the shift must point at the restored plan");
        });
    }

    @GameTest(maxTicks = 200)
    public void deathRecordReachesThePrompt(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            var state = FriendaiServerState.get(h.getLevel().getServer());
            String prompt = new CompanionPerception(
                            back.get(),
                            () -> new PerceptionContext(state.resources(), state.settings(), state.freeTime()))
                    .describe();
            h.assertTrue(prompt.contains("Last death:"), "the prompt should carry the death, was: " + prompt);
            h.assertTrue(
                    prompt.contains(h.getLevel().dimension().identifier().getPath()),
                    "the death line should name the dimension");
        });
    }

    @GameTest(maxTicks = 3600)
    public void firstTurnAfterDeathIsAnswered(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        FriendaiServerState state = FriendaiServerState.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        UUID dead = companion.getUUID();
        Script script = new Script();
        Said said = new Said();
        state.sessions().setGatewayOverride(owner.getUUID(), script);
        state.sessions().setSpeakerWrapper(owner.getUUID(), real -> said);

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            if (back.isEmpty()) {
                return;
            }
            if (said.said.isEmpty()) {
                ChatRouter.onChat(
                        ServerRuntimeRegistry.getOrCreate(owner.level().getServer()), owner, "Friendai, hi");
            }
            h.assertTrue(said.said.contains("Still here"), "the rebuilt brain must answer, said " + said.said);
        });
    }

    @GameTest
    public void carryStoreRoundTripsThroughItsCodec(GameTestHelper h) {
        CompanionCarryData data = new CompanionCarryData();
        UUID owner = UUID.randomUUID();
        CompoundTag body = new CompoundTag();
        body.putString("Probe", "kept");
        DeathRecord death = new DeathRecord("overworld", 1, 2, 3, "lava", "", 40);
        data.record(owner, body, death);

        Tag encoded = CompanionCarryData.TYPE
                .codec()
                .encodeStart(NbtOps.INSTANCE, data)
                .result()
                .orElseThrow();
        CompanionCarryData decoded = CompanionCarryData.TYPE
                .codec()
                .parse(NbtOps.INSTANCE, encoded)
                .result()
                .orElseThrow();

        h.assertValueEqual(
                decoded.carry(owner).orElseThrow().getString("Probe").orElse(""), "kept", "the body must round-trip");
        h.assertValueEqual(decoded.lastDeath(owner).orElseThrow().describe(), death.describe(), "the death");

        CompoundTag newer = new CompoundTag();
        newer.putInt("version", 2);
        newer.put("owners", new CompoundTag());
        CompanionCarryData future = CompanionCarryData.TYPE
                .codec()
                .parse(NbtOps.INSTANCE, newer)
                .result()
                .orElseThrow();
        future.record(owner, body, death);
        h.assertTrue(future.carry(owner).isEmpty(), "a newer format must not be written by this build");
        Tag reencoded = CompanionCarryData.TYPE
                .codec()
                .encodeStart(NbtOps.INSTANCE, future)
                .result()
                .orElseThrow();
        h.assertValueEqual(
                ((CompoundTag) reencoded).getInt("version").orElse(0), 2, "the newer version header must be kept");
        h.succeed();
    }
}
