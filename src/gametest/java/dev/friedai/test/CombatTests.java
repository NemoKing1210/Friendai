package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.TaskListener;
import dev.friedai.task.combat.CombatController;
import dev.friedai.task.combat.Threats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Survival and combat behaviour. Every test spawns its own mobs in its own area. */
public class CombatTests {
    private static final class Notices implements TaskListener {
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        final List<ItemStack> heldAtStart = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFinished(int id, String toolName, dev.friedai.task.TaskStatus status, String summary) {}

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onNotice(String text) {
            texts.add(text);
        }
    }

    private static void assertClose(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, what + " was " + actual + ", expected " + expected);
    }

    private static Notices listen(CompanionEntity c) {
        Notices notices = new Notices();
        c.taskRunner().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, dev.friedai.task.TaskStatus status, String summary) {}

            @Override
            public void onCancelled(int id, String toolName, String reason) {}

            @Override
            public void onNotice(String text) {
                notices.texts.add(text);
                if (text.contains("attacked me")) {
                    notices.heldAtStart.add(c.getMainHandItem().copy());
                }
            }
        });
        return notices;
    }

    @GameTest
    public void attributesLikePlayer(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        assertClose(h, c.getAttributeValue(Attributes.ATTACK_DAMAGE), 1.0, "attack damage");
        assertClose(h, c.getAttributeValue(Attributes.ATTACK_SPEED), 4.0, "attack speed");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void regeneratesSlowly(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.setHealth(10.0F);
        h.runAfterDelay(200, () -> h.assertTrue(c.getHealth() == 10.0F, "healed too early: " + c.getHealth()));
        h.runAfterDelay(460, () -> {
            h.assertTrue(c.getHealth() == 11.0F, "health was " + c.getHealth() + ", expected 11");
            h.succeed();
        });
    }

    @GameTest
    public void equipmentTextFormat(GameTestHelper h) {
        CompanionEntity empty = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(empty.equipmentText().equals("Wearing: nothing; holding: nothing; combat mode: defensive"),
                "empty text was " + empty.equipmentText());

        CompanionEntity dressed = h.spawn(FriedaiEntities.COMPANION, 4, 2, 4);
        dressed.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        dressed.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        dressed.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        dressed.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        h.assertTrue(dressed.equipmentText()
                        .equals("Wearing: iron_helmet, iron_chestplate; holding: iron_sword (offhand: torch); combat mode: defensive"),
                "dressed text was " + dressed.equipmentText());
        h.succeed();
    }

    @GameTest(maxTicks = 800)
    public void fightsBackZombie(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Notices notices = listen(c);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        h.runAfterDelay(20, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.succeedWhen(() -> {
            h.assertTrue(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "zombie still alive: " + zombie.getHealth()
                            + ", companion health " + c.getHealth()
                            + ", state " + c.combatController().state()
                            + ", held " + notices.heldAtStart
                            + ", notices " + notices.texts);
            h.assertTrue(notices.texts.stream().anyMatch(n -> n.startsWith("a zombie attacked me; fighting back")),
                    "notices " + notices.texts);
            h.assertTrue(notices.texts.stream().anyMatch(n -> n.startsWith("fight over: killed 1 zombie")),
                    "notices " + notices.texts);
            h.assertTrue(notices.heldAtStart.stream().anyMatch(s -> s.is(Items.STONE_SWORD)),
                    "held during the fight: " + notices.heldAtStart);
            h.assertTrue(c.getMainHandItem().isEmpty(), "main hand not restored: " + c.getMainHandItem());
        });
    }

    @GameTest(maxTicks = 800)
    public void defendsOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Notices notices = listen(c);
        Husk husk = h.spawnWithNoFreeWill(EntityTypes.HUSK, 6, 2, 2);
        h.runAfterDelay(100, () -> owner.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(husk), 1.0F));
        h.succeedWhen(() -> {
            h.assertTrue(husk.isDeadOrDying() || husk.isRemoved(), "husk still alive: " + husk.getHealth());
            String expected = "a husk attacked " + owner.getName().getString() + "; defending";
            h.assertTrue(notices.texts.stream().anyMatch(n -> n.startsWith(expected)), "notices " + notices.texts);
        });
    }

    @GameTest(maxTicks = 800)
    public void guardAttacksUnprovoked(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatMode(dev.friedai.task.combat.CombatMode.GUARD);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        ZombieVillager zombieVillager = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE_VILLAGER, 8, 2, 2);
        h.succeedWhen(() -> h.assertTrue(zombieVillager.isDeadOrDying() || zombieVillager.isRemoved(),
                "guard did not attack: " + zombieVillager.getHealth()
                        + ", companion health " + c.getHealth()
                        + ", state " + c.combatController().state()));
    }

    @GameTest(maxTicks = 300)
    public void defensiveIgnoresUnprovoked(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        var unprovoked = h.spawnWithNoFreeWill(EntityTypes.CREEPER, 12, 2, 2);
        h.runAfterDelay(200, () -> {
            h.assertTrue(unprovoked.getHealth() == unprovoked.getMaxHealth(),
                    "unprovoked mob was attacked: " + unprovoked.getHealth()
                            + ", state " + c.combatController().state()
                            + ", target " + c.combatController().target()
                            + ", companion attacker " + c.getLastHurtByMob()
                            + ", companion health " + c.getHealth()
                            + ", drowned target " + unprovoked.getTarget()
                            + ", notices " + notices.texts
                            + ", mode " + c.combatMode());
            h.assertTrue(c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void guardIgnoresProtected(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 14, 2, 14);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatMode(dev.friedai.task.combat.CombatMode.GUARD);
        Wolf wolf = h.spawn(EntityTypes.WOLF, 6, 2, 2);
        wolf.tame(other);
        Villager villager = h.spawn(EntityTypes.VILLAGER, 7, 2, 3);
        h.assertTrue(Threats.isProtected(wolf, c), "another player's wolf must be protected");
        h.assertTrue(Threats.isProtected(villager, c), "a villager must be protected");
        h.runAfterDelay(200, () -> {
            h.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "wolf was attacked: " + wolf.getHealth());
            h.assertTrue(villager.getHealth() == villager.getMaxHealth(), "villager was attacked: " + villager.getHealth());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void neverHitsOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        h.runAfterDelay(10, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(owner), 1.0F));
        h.runAfterDelay(80, () -> {
            h.assertTrue(owner.getHealth() == owner.getMaxHealth(), "owner was hit: " + owner.getHealth());
            h.assertTrue(c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 800)
    public void targetKilledByOthersEndsFight(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        zombie.setHealth(200.0F);
        h.runAfterDelay(20, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(60, () -> zombie.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000.0F));
        h.runAfterDelay(120, () -> {
            h.assertTrue(c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.assertTrue(!c.taskRunner().paused(), "the runner must be unpaused");
            h.assertTrue(c.getMainHandItem().isEmpty(), "main hand not restored: " + c.getMainHandItem());
            h.succeed();
        });
    }
}
