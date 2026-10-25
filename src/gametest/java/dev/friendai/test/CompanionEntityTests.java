package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.entity.LookAtOwnerGoal;
import dev.friendai.task.builtin.StayTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;

public class CompanionEntityTests {
    private static long companionsOf(ServerLevel level, ServerPlayer owner) {
        return level.getEntities(EntityTypeTest.forClass(CompanionEntity.class), e -> owner.getUUID().equals(e.ownerUuid()))
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

        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));

        h.assertTrue(player.getUUID().equals(b.ownerUuid()), "owner not restored");
        h.assertTrue(b.inventory().countItem(Items.OAK_LOG) == 5, "inventory not restored");
        h.succeed();
    }

    @GameTest
    public void workingCompanionDoesNotTurnToTheOwner(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(player.getUUID());

        LookAtOwnerGoal goal = new LookAtOwnerGoal(companion);
        companion.taskRunner().start(new StayTask(), "stay");
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
        a.setCombatMode(dev.friendai.task.combat.CombatMode.GUARD);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(b.combatMode() == dev.friendai.task.combat.CombatMode.GUARD,
                "combat mode was " + b.combatMode());

        CompanionEntity fresh = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        TagValueOutput plain = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        fresh.saveWithoutId(plain);
        CompanionEntity loaded = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), plain.buildResult()));
        h.assertTrue(loaded.combatMode() == dev.friendai.task.combat.CombatMode.DEFENSIVE,
                "missing mode should default to defensive, was " + loaded.combatMode());
        h.succeed();
    }

    @GameTest
    public void equipmentDropsOnDeath(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000.0F);
        h.succeedWhen(() -> {
            boolean found = h.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(6.0))
                    .stream()
                    .anyMatch(e -> e.getItem().is(Items.IRON_BOOTS));
            h.assertTrue(found, "iron boots did not drop");
        });
    }
}
