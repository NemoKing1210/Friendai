package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.entity.LookAtOwnerGoal;
import dev.friendai.task.builtin.JumpTask;
import dev.friendai.task.builtin.StayTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public class CompanionEntityTests {
    private static long companionsOf(ServerLevel level, ServerPlayer owner) {
        return level
                .getEntities(
                        EntityTypeTest.forClass(CompanionEntity.class),
                        e -> owner.getUUID().equals(e.ownerUuid()))
                .stream()
                .filter(e -> !e.isRemoved())
                .count();
    }

    /** The mock player logs in at the world origin; move it into the (loaded) test area. */
    @SuppressWarnings("removal")
    static ServerPlayer mockPlayerHere(GameTestHelper h) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(2, 2, 2));
        player.teleportTo(pos.x, pos.y, pos.z);
        return player;
    }

    @GameTest
    public void summonCreatesOneCompanionPerOwner(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(player);
        manager.summon(player);
        h.runAfterDelay(2, () -> {
            h.assertTrue(companionsOf(h.getLevel(), player) == 1, "expected exactly one companion");
            h.succeed();
        });
    }

    @GameTest
    public void companionSavesOwnerAndInventory(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.setOwner(player.getUUID());
        a.inventory().addItem(new ItemStack(Items.OAK_LOG, 5));

        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));

        h.assertTrue(player.getUUID().equals(b.ownerUuid()), "owner not restored");
        h.assertTrue(b.inventory().countItem(Items.OAK_LOG) == 5, "inventory not restored");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void jumpTaskLeavesTheGroundAndLands(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        double ground = companion.getY();
        boolean[] airborne = {false};
        companion.executionCoordinator().start(new JumpTask(), "jump");
        h.succeedWhen(() -> {
            if (companion.getY() > ground + 0.3) {
                airborne[0] = true;
            }
            h.assertTrue(airborne[0], "the companion should leave the ground, y=" + companion.getY());
            h.assertTrue(companion.onGround(), "the companion should land again, y=" + companion.getY());
        });
    }

    @GameTest
    public void workingCompanionDoesNotTurnToTheOwner(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(player.getUUID());

        LookAtOwnerGoal goal = new LookAtOwnerGoal(companion);
        companion.executionCoordinator().start(new StayTask(), "stay");
        for (int tick = 0; tick < 200; tick++) {
            h.assertFalse(goal.canUse(), "a companion with a task must keep its eyes on the job, not the owner");
        }
        h.succeed();
    }

    @GameTest
    public void idleCompanionGlancesAtItsOwner(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(player.getUUID());

        LookAtOwnerGoal goal = new LookAtOwnerGoal(companion);
        boolean glances = false;
        for (int tick = 0; tick < 500 && !glances; tick++) {
            glances = goal.canUse();
        }
        h.assertTrue(glances, "an idle companion standing next to its owner should look at them now and then");
        h.succeed();
    }

    @GameTest
    public void collectNearbyItemsMovesStacks(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var pos = companion.position();
        ItemEntity item = new ItemEntity(h.getLevel(), pos.x + 1, pos.y, pos.z, new ItemStack(Items.COBBLESTONE, 3));
        h.getLevel().addFreshEntity(item);
        int moved = companion.collectNearbyItems(2.0);
        h.assertTrue(moved == 3, "moved " + moved + " items, expected 3");
        h.assertTrue(item.isRemoved(), "item entity should be removed");
        h.assertTrue(companion.inventory().countItem(Items.COBBLESTONE) == 3, "cobblestone not in inventory");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void filteredCollectionWaitsForThePickupDelay(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var pos = companion.position();
        ItemEntity item = new ItemEntity(h.getLevel(), pos.x + 1, pos.y, pos.z, new ItemStack(Items.COBBLESTONE, 2));
        item.setDefaultPickUpDelay();
        h.getLevel().addFreshEntity(item);
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            companion.collectNearbyItems(2.0, e -> !e.hasPickUpDelay());
            if (ticks[0] < 8) {
                h.assertValueEqual(companion.inventory().countItem(Items.COBBLESTONE), 0, "collected too early");
            }
            if (ticks[0] >= 15) {
                h.assertValueEqual(
                        companion.inventory().countItem(Items.COBBLESTONE), 2, "not collected after the delay");
            }
        });
    }

    @GameTest
    public void dismissRemovesCompanion(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(player);
        h.assertTrue(manager.dismiss(player), "dismiss should report success");
        h.assertTrue(manager.find(player.getUUID()).isEmpty(), "companion should be gone");
        h.assertTrue(!manager.dismiss(player), "second dismiss should report no companion");
        h.succeed();
    }

    @SuppressWarnings("unused")
    private static void unused(EntityType<?> t, Entity e) {}

    @GameTest
    public void combatModeSurvivesSave(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.setCombatMode(dev.friendai.features.combat.CombatMode.GUARD);
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(
                b.combatMode() == dev.friendai.features.combat.CombatMode.GUARD, "combat mode was " + b.combatMode());

        CompanionEntity fresh = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        TagValueOutput plain = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        fresh.saveWithoutId(plain);
        CompanionEntity loaded = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        loaded.load(
                TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), plain.buildResult()));
        h.assertTrue(
                loaded.combatMode() == dev.friendai.features.combat.CombatMode.DEFENSIVE,
                "missing mode should default to defensive, was " + loaded.combatMode());
        h.succeed();
    }

    @GameTest
    public void combatSettingsRoundTrip(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        dev.friendai.features.combat.CombatSettings settings = new dev.friendai.features.combat.CombatSettings(
                dev.friendai.features.combat.ProtectionPolicy.SELF_ONLY,
                dev.friendai.features.combat.FightingStyle.RANGED,
                true,
                40,
                dev.friendai.features.combat.RetreatPreference.CONSERVATIVE);
        a.setCombatSettings(settings);
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertValueEqual(b.combatSettings(), settings, "combat settings did not round-trip");
        h.succeed();
    }

    @GameTest
    public void legacyCombatModeKeyIsIgnored(GameTestHelper h) {
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        out.putString("CombatMode", "guard");
        CompanionEntity loaded = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        loaded.load(
                TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertValueEqual(
                loaded.combatSettings(),
                dev.friendai.features.combat.CombatSettings.defaults(),
                "the target format ignores the retired legacy key");
        h.succeed();
    }

    @GameTest
    public void malformedCombatSettingsUseSafeDefaults(GameTestHelper h) {
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        out.putString(
                "CombatSettings", "protection=guard;style=balanced;splashPotions=false;pursuit=99;retreat=standard");
        out.putString("CombatMode", "guard");
        CompanionEntity loaded = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        loaded.load(
                TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertValueEqual(
                loaded.combatSettings(),
                dev.friendai.features.combat.CombatSettings.defaults(),
                "malformed settings must fall back to the whole safe default");
        h.assertValueEqual(
                loaded.combatSettings().protection(),
                dev.friendai.features.combat.ProtectionPolicy.BODYGUARD,
                "malformed data must not silently grant guard behaviour");
        h.succeed();
    }

    @GameTest
    public void settingsRemainIndependentPerOwner(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        a.setCombatSettings(a.combatSettings()
                .withProtection(dev.friendai.features.combat.ProtectionPolicy.SELF_ONLY)
                .withPursuitLimit(12));
        b.setCombatSettings(b.combatSettings()
                .withProtection(dev.friendai.features.combat.ProtectionPolicy.GUARD)
                .withPursuitLimit(48));
        h.assertValueEqual(
                a.combatSettings().protection(),
                dev.friendai.features.combat.ProtectionPolicy.SELF_ONLY,
                "first owner settings changed");
        h.assertValueEqual(
                b.combatSettings().protection(),
                dev.friendai.features.combat.ProtectionPolicy.GUARD,
                "second owner settings changed");
        h.assertValueEqual(a.combatSettings().pursuitLimitBlocks(), 12, "first pursuit changed");
        h.assertValueEqual(b.combatSettings().pursuitLimitBlocks(), 48, "second pursuit changed");
        h.succeed();
    }
}
