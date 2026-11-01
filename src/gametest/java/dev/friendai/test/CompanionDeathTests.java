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
import dev.friendai.server.runtime.ServerRuntime;
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
        GameTestSupport.runtime(h.getLevel().getServer())
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
    public void deathDropsLootButKeepsBodyState(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
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
            h.assertValueEqual(
                    revived.inventory().countItem(Items.OAK_LOG), 0, "the replacement body starts with an empty bag");
            h.assertTrue(
                    revived.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                    "the replacement body does not wear what was dropped");
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
                            .anyMatch(item -> item.getItem().is(Items.OAK_LOG)
                                    && item.getItem().getCount() == 5),
                    "the logs must lie where the companion died");
            h.assertTrue(
                    h.getLevel().getEntitiesOfClass(ItemEntity.class, spot).stream()
                            .anyMatch(item -> item.getItem().is(Items.IRON_BOOTS)),
                    "the boots must lie where the companion died");

            TagValueOutput out = TagValueOutput.createWithContext(
                    ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            revived.saveWithoutId(out);
            CompanionEntity copy = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
            copy.load(TagValueInput.create(
                    ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            h.assertValueEqual(copy.inventory().countItem(Items.OAK_LOG), 0, "a world reload keeps the empty bag");
            h.assertTrue(
                    copy.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                    "a world reload does not resurrect the dropped boots");
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

    @GameTest(maxTicks = 600)
    public void summonAfterDeathRestoresWhenAutoRespawnIsOff(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
        state.companionLife().setAutoRespawn(owner.getUUID(), false);
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 4));
        UUID dead = companion.getUUID();
        AABB area = companion.getBoundingBox().inflate(8.0);

        kill(companion, h);

        h.runAfterDelay(60, () -> {
            h.assertTrue(
                    replacement(manager, owner.getUUID(), dead).isEmpty(),
                    "auto-respawn is off, so no replacement may appear");
            h.assertTrue(manager.find(owner.getUUID()).isEmpty(), "the dead body should be removed by now");
            CompanionEntity summoned = manager.summon(owner);
            h.assertTrue(!summoned.getUUID().equals(dead), "summoning after a death must create a new body");
            h.succeedWhen(() -> {
                int carried = summoned.inventory().countItem(Items.OAK_LOG);
                int ground = h.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                        .filter(item -> item.getItem().is(Items.OAK_LOG))
                        .mapToInt(item -> item.getItem().getCount())
                        .sum();
                h.assertValueEqual(
                        carried + ground, 4, "the four logs must be either fetched or still where the companion died");
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void deathDropsEveryCarriedStackExactlyOnce(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        int carried = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            int count = slot + 1;
            companion.inventory().setItem(slot, new ItemStack(Items.OAK_LOG, count));
            carried += count;
        }
        companion.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        companion.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        companion.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        companion.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        companion.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        AABB spot = companion.getBoundingBox().inflate(8.0);
        UUID dead = companion.getUUID();
        int expected = carried;

        kill(companion, h);

        h.succeedWhen(() -> {
            List<ItemEntity> drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, spot);
            int logs = drops.stream()
                    .filter(item -> item.getItem().is(Items.OAK_LOG))
                    .mapToInt(item -> item.getItem().getCount())
                    .sum();
            h.assertValueEqual(logs, expected, "the bag must be dropped once, not doubled or kept");
            // Item entities merge when they land on each other, so only the total proves exactly-once.
            h.assertTrue(
                    drops.stream().anyMatch(item -> item.getItem().is(Items.OAK_LOG)),
                    "the bag must leave the body as drops");
            for (var worn : List.of(
                    Items.IRON_HELMET,
                    Items.IRON_CHESTPLATE,
                    Items.IRON_LEGGINGS,
                    Items.IRON_BOOTS,
                    Items.DIAMOND_SWORD,
                    Items.SHIELD)) {
                long count =
                        drops.stream().filter(item -> item.getItem().is(worn)).count();
                h.assertTrue(count == 1L, "each worn piece must drop exactly once: " + worn + ", got " + count);
            }
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            h.assertValueEqual(
                    back.get().inventory().countItem(Items.OAK_LOG), 0, "the replacement body carries nothing");
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                h.assertTrue(
                        back.get().getItemBySlot(slot).isEmpty(),
                        "the replacement body must not keep " + slot + " after dropping it");
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void recoveryWalksBackAndCollectsTheDrops(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 6));
        companion.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        UUID dead = companion.getUUID();
        CompanionCarryData store = CompanionCarryData.get(h.getLevel().getServer());

        kill(companion, h);

        h.succeedWhen(() -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion should respawn automatically");
            CompanionEntity revived = back.get();
            h.assertTrue(
                    revived.inventory().countItem(Items.OAK_LOG) >= 6,
                    "the logs must be fetched back, carried="
                            + revived.inventory().countItem(Items.OAK_LOG));
            // The trip is guaranteed and reported; a drop the route could not reach stays on the ground and says so.
            boolean bootsCarried = revived.inventory().countItem(Items.IRON_BOOTS) >= 1;
            boolean bootsOnGround =
                    h
                            .getLevel()
                            .getEntitiesOfClass(
                                    ItemEntity.class, revived.getBoundingBox().inflate(24))
                            .stream()
                            .anyMatch(item -> item.getItem().is(Items.IRON_BOOTS));
            h.assertTrue(
                    bootsCarried || bootsOnGround,
                    "the boots must be fetched or reported left behind, carried="
                            + revived.inventory().countItem(Items.IRON_BOOTS));
            h.assertTrue(store.openDrops(owner.getUUID()).isEmpty(), "a finished recovery clears the record");
        });
    }

    @GameTest(maxTicks = 600)
    public void aSummonedBodyFetchesTheDropsItLeftBehind(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
        state.companionLife().setAutoRespawn(owner.getUUID(), false);
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 2));
        UUID dead = companion.getUUID();

        kill(companion, h);

        h.runAfterDelay(40, () -> {
            h.assertTrue(replacement(manager, owner.getUUID(), dead).isEmpty(), "auto-respawn is off here");
            CompanionCarryData store = CompanionCarryData.get(h.getLevel().getServer());
            h.assertTrue(store.openDrops(owner.getUUID()).isPresent(), "the death must leave a record to fetch");
            CompanionEntity summoned = manager.summon(owner);
            h.assertTrue(manager.find(owner.getUUID()).isPresent(), "the summoned body must be indexed");

            var open = store.openDrops(owner.getUUID()).orElseThrow();
            double away = summoned.position()
                    .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(new BlockPos(open.x(), open.y(), open.z())));
            h.assertTrue(away <= 24, "the drops must be within the detour radius, away=" + away);
            h.succeedWhen(() -> h.assertTrue(
                    summoned.inventory().countItem(Items.OAK_LOG) >= 2,
                    "an idle body must fetch drops left nearby, carried="
                            + summoned.inventory().countItem(Items.OAK_LOG)));
        });
    }

    @GameTest(maxTicks = 200)
    public void aRefusalLeavesTheDropsAlone(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 3));
        UUID dead = companion.getUUID();
        CompanionCarryData store = CompanionCarryData.get(h.getLevel().getServer());

        kill(companion, h);
        store.setRefuseRecovery(owner.getUUID(), true);

        h.runAfterDelay(100, () -> {
            Optional<CompanionEntity> back = replacement(manager, owner.getUUID(), dead);
            h.assertTrue(back.isPresent(), "the companion still respawns when the owner refuses the trip");
            h.assertValueEqual(
                    back.get().inventory().countItem(Items.OAK_LOG), 0, "a refused recovery must not fetch the logs");
            h.assertTrue(
                    store.refusedRecovery(owner.getUUID()), "the refusal must survive until the owner reverses it");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aSecondDeathReplacesTheOpenRecovery(GameTestHelper h) {
        ready(h);
        CompanionCarryData store = CompanionCarryData.get(h.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        CompoundTag body = new CompoundTag();
        DeathRecord first = new DeathRecord(
                "overworld",
                1,
                2,
                3,
                "lava",
                "",
                10,
                new DeathRecord.Drops(List.of(UUID.randomUUID()), List.of("logs x2"), false),
                null);
        DeathRecord second = new DeathRecord(
                "overworld",
                4,
                5,
                6,
                "fall",
                "",
                20,
                new DeathRecord.Drops(List.of(UUID.randomUUID()), List.of("stone x1"), false),
                null);

        store.record(owner, body, first);
        store.record(owner, body, second);

        h.assertValueEqual(store.openDrops(owner).orElseThrow().x(), 4, "the newest death is the one that is fetched");
        store.clearDrops(owner);
        h.assertTrue(store.openDrops(owner).isEmpty(), "a finished recovery leaves nothing open");
        h.assertTrue(store.lastDeath(owner).isPresent(), "the death itself stays in the log");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void dropsTakenBeforeTheTripClearTheRecord(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 2));
        UUID dead = companion.getUUID();
        CompanionCarryData store = CompanionCarryData.get(h.getLevel().getServer());

        kill(companion, h);

        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (ticks[0] == 40) {
                for (ItemEntity item : h.getLevel()
                        .getEntitiesOfClass(
                                ItemEntity.class, companion.getBoundingBox().inflate(16))) {
                    item.discard();
                }
                h.assertTrue(store.openDrops(owner.getUUID()).isPresent(), "the death still has an open record");
                manager.summon(owner);
            }
            if (ticks[0] > 40) {
                h.assertTrue(
                        store.openDrops(owner.getUUID()).isEmpty(),
                        "a trip to an empty spot clears the record instead of retrying forever");
            }
        });
    }

    @GameTest(maxTicks = 900)
    public void aFullInventoryLeavesTheDropsOnTheGround(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity companion = manager.summon(owner);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG, 2));
        UUID dead = companion.getUUID();
        CompanionCarryData store = CompanionCarryData.get(h.getLevel().getServer());

        kill(companion, h);

        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (ticks[0] == 40) {
                CompanionEntity summoned = manager.summon(owner);
                for (int slot = 0; slot < summoned.inventory().getContainerSize(); slot++) {
                    summoned.inventory().setItem(slot, new ItemStack(Items.STONE, 64));
                }
            }
            if (ticks[0] > 40) {
                h.assertTrue(store.openDrops(owner.getUUID()).isEmpty(), "a trip with no room still ends and reports");
                h.assertTrue(
                        h
                                .getLevel()
                                .getEntitiesOfClass(
                                        ItemEntity.class,
                                        companion.getBoundingBox().inflate(24))
                                .stream()
                                .anyMatch(item -> item.getItem().is(Items.OAK_LOG)),
                        "logs that did not fit stay on the ground");
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void deathKeepsPerOwnerStores(GameTestHelper h) {
        ServerPlayer owner = ready(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
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
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
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
            var state = GameTestSupport.runtime(h.getLevel().getServer());
            String prompt = new CompanionPerception(
                            back.get(),
                            () -> new PerceptionContext(state.resourceCandidates(), state.settings(), state.freeTime()))
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
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
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
        // One past the current format: a file this build cannot write must stay untouched.
        newer.putInt("version", 3);
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
                ((CompoundTag) reencoded).getInt("version").orElse(0), 3, "the newer version header must be kept");
        h.succeed();
    }
}
