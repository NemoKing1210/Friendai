package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.movement.PaceMode;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.AttackPlayerTask;
import dev.friendai.task.builtin.EatTask;
import dev.friendai.task.builtin.EquipTask;
import dev.friendai.task.builtin.HuntTask;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.combat.CombatController;
import dev.friendai.task.combat.Food;
import dev.friendai.task.combat.Threats;
import dev.friendai.task.mining.BlockMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

/** Survival and combat behaviour. Every test spawns its own mobs in its own area. */
public class CombatTests {
    private static final class Notices implements TaskListener {
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        final List<ItemStack> heldAtStart = Collections.synchronizedList(new ArrayList<>());

        TaskStatus finalStatus;
        String finalSummary;
        final List<String> cancelled = Collections.synchronizedList(new ArrayList<>());

        void reset() {
            finalStatus = null;
            finalSummary = null;
        }

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            finalStatus = status;
            finalSummary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled.add(toolName + ":" + reason);
        }

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
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {
                notices.finalStatus = status;
                notices.finalSummary = summary;
            }

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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        assertClose(h, c.getAttributeValue(Attributes.ATTACK_DAMAGE), 1.0, "attack damage");
        assertClose(h, c.getAttributeValue(Attributes.ATTACK_SPEED), 4.0, "attack speed");
        h.succeed();
    }

    @GameTest
    public void equipmentTextFormat(GameTestHelper h) {
        CompanionEntity empty = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                empty.equipmentText().equals("Wearing: nothing; holding: nothing; combat: bodyguard/balanced"),
                "empty text was " + empty.equipmentText());

        CompanionEntity dressed = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        dressed.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        dressed.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        dressed.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        dressed.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        h.assertTrue(
                dressed.equipmentText()
                        .equals(
                                "Wearing: iron_helmet, iron_chestplate; holding: iron_sword (offhand: torch); combat: bodyguard/balanced"),
                "dressed text was " + dressed.equipmentText());

        CompanionEntity runner = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        runner.setPaceMode(PaceMode.RUN);
        h.assertTrue(
                runner.equipmentText()
                        .equals("Wearing: nothing; holding: nothing; combat: bodyguard/balanced; pace: run"),
                "run text was " + runner.equipmentText());
        h.succeed();
    }

    @GameTest(maxTicks = 800)
    public void fightsBackZombie(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Notices notices = listen(c);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setCustomName(Component.literal("test-fight-zombie"));
        roof(h, 5, 2);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.succeedWhen(() -> {
            h.assertTrue(
                    zombie.isDeadOrDying() || zombie.isRemoved(),
                    "zombie still alive: " + zombie.getHealth()
                            + ", companion health " + c.getHealth()
                            + ", state " + c.combatController().state()
                            + ", held " + notices.heldAtStart
                            + ", notices " + notices.texts);
            h.assertTrue(
                    notices.texts.stream().anyMatch(n -> n.startsWith("a zombie attacked me; fighting back")),
                    "notices " + notices.texts);
            h.assertTrue(
                    notices.texts.stream().anyMatch(n -> n.startsWith("fight over: killed 1 zombie")),
                    "notices " + notices.texts);
            h.assertTrue(
                    notices.heldAtStart.stream().anyMatch(s -> s.is(Items.STONE_SWORD)),
                    "held during the fight: " + notices.heldAtStart);
            h.assertTrue(c.getMainHandItem().isEmpty(), "main hand not restored: " + c.getMainHandItem());
        });
    }

    @GameTest(maxTicks = 800)
    public void defendsOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Notices notices = listen(c);
        Husk husk = h.spawnWithNoFreeWill(EntityTypes.HUSK, 6, 2, 2);
        husk.setCustomName(Component.literal("test-owner-husk"));
        // removeFreeWill clears goals but leaves target goals; NoAI stops them picking the companion.
        husk.setNoAi(true);
        h.runAfterDelay(
                100,
                () -> owner.hurtServer(
                        h.getLevel(), h.getLevel().damageSources().mobAttack(husk), 1.0F));
        h.succeedWhen(() -> {
            h.assertTrue(
                    husk.isDeadOrDying() || husk.isRemoved(),
                    "husk still alive: " + husk.getHealth()
                            + ", state " + c.combatController().state()
                            + ", target " + c.combatController().target()
                            + ", distance " + c.distanceTo(husk)
                            + ", companion health " + c.getHealth() + "/alive " + c.isAlive()
                            + ", main hand " + c.getMainHandItem());
            String expected = "a husk attacked " + owner.getName().getString() + "; defending";
            h.assertTrue(notices.texts.stream().anyMatch(n -> n.startsWith(expected)), "notices " + notices.texts);
        });
    }

    @GameTest(maxTicks = 800)
    public void guardAttacksUnprovoked(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatMode(dev.friendai.task.combat.CombatMode.GUARD);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        ZombieVillager zombieVillager = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE_VILLAGER, 8, 2, 2);
        roof(h, 8, 2);
        h.succeedWhen(() -> h.assertTrue(
                zombieVillager.isDeadOrDying() || zombieVillager.isRemoved(),
                "guard did not attack: " + zombieVillager.getHealth()
                        + ", companion health " + c.getHealth()
                        + ", state " + c.combatController().state()));
    }

    @GameTest(maxTicks = 300)
    public void defensiveIgnoresUnprovoked(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        var unprovoked = h.spawnWithNoFreeWill(EntityTypes.CREEPER, 12, 2, 2);
        unprovoked.setCustomName(Component.literal("test-unprovoked-creeper"));
        // removeFreeWill clears goals but leaves target goals; NoAI stops them picking the companion.
        unprovoked.setNoAi(true);
        h.runAfterDelay(200, () -> {
            h.assertTrue(
                    unprovoked.getHealth() == unprovoked.getMaxHealth(),
                    "unprovoked mob was attacked: " + unprovoked.getHealth()
                            + ", state " + c.combatController().state()
                            + ", target " + c.combatController().target()
                            + ", companion attacker " + c.getLastHurtByMob()
                            + ", companion health " + c.getHealth()
                            + ", drowned target " + unprovoked.getTarget()
                            + ", notices " + notices.texts
                            + ", mode " + c.combatMode());
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void guardIgnoresProtected(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 14, 2, 14);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatMode(dev.friendai.task.combat.CombatMode.GUARD);
        Wolf wolf = h.spawn(EntityTypes.WOLF, 6, 2, 2);
        wolf.tame(other);
        Villager villager = h.spawn(EntityTypes.VILLAGER, 7, 2, 3);
        h.assertTrue(Threats.isProtected(wolf, c), "another player's wolf must be protected");
        h.assertTrue(Threats.isProtected(villager, c), "a villager must be protected");
        h.runAfterDelay(200, () -> {
            h.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "wolf was attacked: " + wolf.getHealth());
            h.assertTrue(
                    villager.getHealth() == villager.getMaxHealth(), "villager was attacked: " + villager.getHealth());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void neverHitsOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        h.runAfterDelay(
                10,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(owner), 1.0F));
        h.runAfterDelay(80, () -> {
            h.assertTrue(owner.getHealth() == owner.getMaxHealth(), "owner was hit: " + owner.getHealth());
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 800)
    public void targetKilledByOthersEndsFight(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setCustomName(Component.literal("test-other-kill-zombie"));
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        zombie.setHealth(200.0F);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(60, () -> zombie.kill(h.getLevel()));
        h.runAfterDelay(120, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.assertTrue(!c.taskRunner().paused(), "the runner must be unpaused");
            h.assertTrue(c.getMainHandItem().isEmpty(), "main hand not restored: " + c.getMainHandItem());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 800)
    public void shootsFarSkeleton(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BOW));
        c.inventory().addItem(new ItemStack(Items.ARROW, 5));
        Skeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 13, 2, 2);
        skeleton.setCustomName(Component.literal("test-bow-skeleton"));
        roof(h, 13, 2);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(skeleton), 1.0F));
        h.succeedWhen(() -> {
            int arrows = c.inventory().countItem(Items.ARROW);
            boolean arrowFlying = !h.getLevel()
                    .getEntitiesOfClass(AbstractArrow.class, c.getBoundingBox().inflate(20.0))
                    .isEmpty();
            h.assertTrue(
                    arrows < 5 || arrowFlying || skeleton.getHealth() < skeleton.getMaxHealth(),
                    "no arrow: arrows " + arrows + ", skeleton health " + skeleton.getHealth() + ", held "
                            + c.getMainHandItem());
        });
    }

    @GameTest(maxTicks = 400)
    public void passiveRunsAway(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 7);
        c.setCombatMode(dev.friendai.task.combat.CombatMode.PASSIVE);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 10, 2, 7);
        zombie.setCustomName(Component.literal("test-passive-zombie"));
        roof(h, 10, 7);
        float before = c.distanceTo(zombie);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(160, () -> {
            h.assertTrue(
                    c.distanceTo(zombie) > before + 1.0,
                    "distance before " + before + ", after " + c.distanceTo(zombie));
            h.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "zombie was hit: " + zombie.getHealth());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void zombieTargetsTheCompanionUnprovoked(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 7);
        Zombie zombie = h.spawn(EntityTypes.ZOMBIE, 11, 2, 7);
        zombie.setCustomName(Component.literal("test-aggro-zombie"));
        h.succeedWhen(() -> h.assertTrue(
                zombie.getTarget() == c,
                "zombie target: " + zombie.getTarget() + ", zombie at " + zombie.position() + ", companion at "
                        + c.position()));
    }

    @GameTest(maxTicks = 80)
    public void creativeCompanionIsIgnoredUnlessGivenAnAttackOrder(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 7);
        c.setCreativeMode(true);
        Zombie zombie = h.spawn(EntityTypes.ZOMBIE, 10, 2, 7);
        zombie.setCustomName(Component.literal("test-creative-ignored-zombie"));

        h.runAfterDelay(20, () -> {
            h.assertTrue(zombie.getTarget() != c, "a hostile mob must not target the companion in creative mode");
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "the companion must ignore nearby mobs without an attack order");
            c.combatController().fight(zombie);
            h.assertTrue(
                    c.combatController().state() == CombatController.State.FIGHTING,
                    "an explicit attack order must still allow a creative companion to fight");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void retreatsAtLowHealth(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 7);
        c.setHealth(4.0F);
        Notices notices = listen(c);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 10, 2, 7);
        zombie.setCustomName(Component.literal("test-retreat-zombie"));
        roof(h, 10, 7);
        zombie.setTarget(c);
        float before = c.distanceTo(zombie);
        h.succeedWhen(() -> {
            h.assertTrue(
                    notices.texts.stream().anyMatch(n -> n.equals("retreating: health 4/20, I have no food")),
                    "notices " + notices.texts);
            h.assertTrue(
                    c.distanceTo(zombie) > before + 1.0,
                    "distance before " + before + ", after " + c.distanceTo(zombie)
                            + ", state " + c.combatController().state()
                            + ", zombie target " + zombie.getTarget()
                            + ", nav done " + c.getNavigation().isDone()
                            + ", notices " + notices.texts);
        });
    }

    @GameTest(maxTicks = 300)
    public void fleesSwellingCreeper(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 7);
        Creeper creeper = h.spawnWithNoFreeWill(EntityTypes.CREEPER, 10, 2, 7);
        creeper.setCustomName(Component.literal("test-swell-creeper"));
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (ticks[0] <= 20) {
                creeper.setSwellDir(1);
            } else if (!creeper.isRemoved()) {
                creeper.discard();
            }
            if (ticks[0] > 60) {
                h.assertTrue(
                        c.distanceTo(creeper) > 6.0,
                        "distance after the creeper was removed: " + c.distanceTo(creeper));
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void unreachableTargetIsDropped(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        boxIn(h, 6, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 2, 2);
        zombie.setCustomName(Component.literal("test-boxed-zombie"));
        h.runAfterDelay(
                10,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(320, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "state was " + c.combatController().state());
            h.assertTrue(!c.taskRunner().paused(), "the runner must be unpaused");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void eatsWhenHurt(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // Hurt, below the regeneration thresholds: only the health reflex can start eating.
        c.hunger().load(10, 0.0F, 0.0F);
        c.setHealth(8.0F);
        c.inventory().addItem(new ItemStack(Items.COOKED_BEEF));
        c.inventory().addItem(new ItemStack(Items.ROTTEN_FLESH));
        h.runAfterDelay(120, () -> {
            h.assertValueEqual(c.hunger().food(), 18, "food after the steak");
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_BEEF), 0, "beef was not eaten");
            h.assertValueEqual(c.inventory().countItem(Items.ROTTEN_FLESH), 1, "rotten flesh must be kept");
            h.assertTrue(c.getHealth() < 16.0F, "eating must not heal directly: health " + c.getHealth());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void doesNotEatUnsafe(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // Hurt and hungry below the regeneration thresholds: food can only change by eating.
        c.hunger().load(10, 0.0F, 0.0F);
        c.setHealth(8.0F);
        c.inventory().addItem(new ItemStack(Items.SPIDER_EYE));
        h.runAfterDelay(120, () -> {
            h.assertValueEqual(c.hunger().food(), 10, "food changed without safe food");
            h.assertValueEqual(c.inventory().countItem(Items.SPIDER_EYE), 1, "the spider eye must be kept");
            h.succeed();
        });
    }

    @GameTest
    public void foodChoiceFollowsTheOwnerRules(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        c.hunger().load(10, 0.0F, 0.0F);
        c.inventory().addItem(new ItemStack(Items.BREAD));
        c.inventory().addItem(new ItemStack(Items.SPIDER_EYE));
        c.setFoodRule("minecraft:bread", false);
        c.setFoodRule("minecraft:spider_eye", true);
        int slot = Food.choose(c, false).orElseThrow();
        h.assertTrue(
                c.inventory().getItem(slot).is(Items.SPIDER_EYE),
                "the chosen food was " + c.inventory().getItem(slot));
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void eatTaskEatsFoodTheOwnerAllowed(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        Notices notices = listen(c);
        c.inventory().addItem(new ItemStack(Items.ROTTEN_FLESH));
        c.setFoodRule("minecraft:rotten_flesh", true);
        c.taskRunner().start(new EatTask(Items.ROTTEN_FLESH), "eat");
        h.runAfterDelay(100, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertValueEqual(
                    c.inventory().countItem(Items.ROTTEN_FLESH), 0, "the allowed rotten flesh must be eaten");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void eatingHoldsFoodInTheUsingHand(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BREAD));
        c.taskRunner().start(new EatTask(Items.BREAD), "eat");
        // The renderer raises the arm from this use state, the same pose a player eating shows.
        h.runAfterDelay(10, () -> {
            ItemStack used = c.getUseItem();
            h.assertTrue(c.isUsingItem(), "the companion is not using an item");
            h.assertTrue(used.is(Items.BREAD), "the used item is " + used);
            h.assertTrue(used.get(DataComponents.FOOD) != null, "the used item is not food: " + used);
            h.assertTrue(used.getUseAnimation() == ItemUseAnimation.EAT, "use animation " + used.getUseAnimation());
            h.assertTrue(c.getUsedItemHand() == InteractionHand.MAIN_HAND, "used hand " + c.getUsedItemHand());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 600)
    public void eatingInterruptedKeepsFood(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(10, 0.0F, 0.0F);
        c.setHealth(9.0F);
        c.inventory().addItem(new ItemStack(Items.BAKED_POTATO));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 4, 2, 2);
        zombie.setCustomName(Component.literal("test-eating-zombie"));
        h.runAfterDelay(
                10,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(16, () -> {
            h.assertTrue(
                    c.inventory().countItem(Items.BAKED_POTATO) == 1,
                    "the baked potato must stay in the inventory, held " + c.getMainHandItem() + ", inventory "
                            + c.inventory().countItem(Items.BAKED_POTATO));
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void wearsBetterArmor(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_CHESTPLATE));
        h.runAfterDelay(60, () -> {
            h.assertTrue(c.getArmorValue() == 6, "armor value was " + c.getArmorValue());
            h.assertTrue(
                    c.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                    "worn " + c.getItemBySlot(EquipmentSlot.CHEST));
            c.inventory().addItem(new ItemStack(Items.LEATHER_CHESTPLATE));
        });
        h.runAfterDelay(160, () -> {
            h.assertTrue(c.getArmorValue() == 6, "the leather chestplate replaced iron: " + c.getArmorValue());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void armorDoesNotFlipFlop(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_CHESTPLATE));
        c.inventory().addItem(new ItemStack(Items.IRON_CHESTPLATE));
        ItemStack cursedHelmet = new ItemStack(Items.IRON_HELMET);
        cursedHelmet.enchant(
                h.getLevel()
                        .registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.BINDING_CURSE),
                1);
        c.setItemSlot(EquipmentSlot.HEAD, cursedHelmet);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_HELMET));

        ItemStack[] worn = new ItemStack[1];
        h.runAfterDelay(60, () -> {
            h.assertTrue(
                    c.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                    "chestplate not worn: " + c.getItemBySlot(EquipmentSlot.CHEST));
            worn[0] = c.getItemBySlot(EquipmentSlot.CHEST);
        });
        h.runAfterDelay(260, () -> {
            h.assertTrue(
                    c.getItemBySlot(EquipmentSlot.CHEST) == worn[0],
                    "the equal chestplate was swapped: " + c.getItemBySlot(EquipmentSlot.CHEST));
            h.assertTrue(
                    c.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                    "the binding helmet was removed: " + c.getItemBySlot(EquipmentSlot.HEAD));
            h.succeed();
        });
    }

    @GameTest(maxTicks = 1200)
    public void huntKillsChickens(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        Chicken first = h.spawnWithNoFreeWill(EntityTypes.CHICKEN, 5, 2, 2);
        Chicken second = h.spawnWithNoFreeWill(EntityTypes.CHICKEN, 7, 2, 2);
        c.taskRunner().start(new HuntTask(type -> type == EntityTypes.CHICKEN, "minecraft:chicken", 2, 16), "attack");
        h.succeedWhen(() -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "status " + notices.finalStatus + " " + notices.finalSummary
                            + ", state " + c.combatController().state()
                            + ", target " + c.combatController().target()
                            + ", first " + first.isRemoved() + "/" + first.getHealth()
                            + ", second " + second.isRemoved() + "/" + second.getHealth());
            h.assertTrue(notices.finalSummary.equals("killed 2 minecraft:chicken"), "summary " + notices.finalSummary);
            h.assertTrue(c.inventory().countItem(Items.CHICKEN) > 0, "no raw chicken collected");
            h.assertTrue(
                    (first.isDeadOrDying() || first.isRemoved()) && (second.isDeadOrDying() || second.isRemoved()),
                    "both chickens must be gone: " + first.isRemoved() + "/" + first.getHealth()
                            + ", " + second.isRemoved() + "/" + second.getHealth()
                            + ", state " + c.combatController().state()
                            + ", target " + c.combatController().target());
        });
    }

    @GameTest(maxTicks = 200)
    public void ownerOrderedHuntAttacksGolem(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var golem = h.spawn(EntityTypes.IRON_GOLEM, 5, 2, 2);
        h.assertTrue(Threats.isProtected(golem, c), "a golem is off limits without an order");
        h.assertFalse(Threats.isProtected(golem, c, true), "the owner's order lifts the golem rule");
        c.taskRunner()
                .start(
                        new HuntTask(type -> type == EntityTypes.IRON_GOLEM, "minecraft:iron_golem", 1, 16, true),
                        "attack");
        h.succeedWhen(() -> {
            h.assertTrue(
                    c.combatController()
                            .target()
                            .filter(target -> target == golem)
                            .isPresent(),
                    "the ordered golem was not targeted: state "
                            + c.combatController().state()
                            + ", target " + c.combatController().target()
                            + ", task "
                            + c.taskRunner().current().map(task -> task.type()).orElse("none"));
            // Leave nothing fighting behind in the shared test world.
            golem.kill(h.getLevel());
            c.taskRunner().stop("test");
        });
    }

    @GameTest(maxTicks = 1200)
    public void huntSkipsNamedMob(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        Rabbit named = h.spawnWithNoFreeWill(EntityTypes.RABBIT, 5, 2, 2);
        named.setCustomName(Component.literal("Bunny"));
        Rabbit other = h.spawnWithNoFreeWill(EntityTypes.RABBIT, 7, 2, 2);
        c.taskRunner().start(new HuntTask(type -> type == EntityTypes.RABBIT, "minecraft:rabbit", 1, 16), "attack");
        h.succeedWhen(() -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "status " + notices.finalStatus + " " + notices.finalSummary
                            + ", state " + c.combatController().state()
                            + ", target " + c.combatController().target()
                            + ", named alive " + named.isAlive()
                            + ", other " + other.isRemoved() + "/" + other.getHealth());
            h.assertTrue(
                    notices.finalSummary.equals("killed 1 minecraft:rabbit; skipped 1 that belong to someone"),
                    "summary " + notices.finalSummary);
            h.assertTrue(named.isAlive(), "the named rabbit must be alive");
            h.assertTrue(
                    other.isDeadOrDying() || other.isRemoved(),
                    "the unnamed rabbit must be gone: removed " + other.isRemoved()
                            + ", health " + other.getHealth() + ", state "
                            + c.combatController().state()
                            + ", target " + c.combatController().target());
        });
    }

    @GameTest(maxTicks = 200)
    public void huntNothingNearby(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        c.taskRunner()
                .start(new HuntTask(type -> type == EntityTypes.MOOSHROOM, "minecraft:mooshroom", 1, 16), "attack");
        h.succeedWhen(() -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.FAILED,
                    "status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("no minecraft:mooshroom within 16 blocks"),
                    "summary " + notices.finalSummary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void attackPlayerHits(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        ServerPlayer target = GameTestSupport.mockPlayerAt(h, 5, 2, 2);
        target.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        target.getAbilities().instabuild = false;
        target.getAbilities().invulnerable = false;
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Notices notices = listen(c);
        List<String> probe = Collections.synchronizedList(new ArrayList<>());
        h.runAfterDelay(150, () -> {
            probe.add("canHarm=" + owner.canHarmPlayer(target)
                    + " pvp=" + h.getLevel().isPvpAllowed()
                    + " targetCreative=" + target.isCreative()
                    + " targetSpectator=" + target.isSpectator()
                    + " ownerTeam=" + owner.getTeam()
                    + " targetTeam=" + target.getTeam()
                    + " ownerMode=" + owner.gameMode.getGameModeForPlayer()
                    + " targetMode=" + target.gameMode.getGameModeForPlayer());
            c.taskRunner()
                    .start(
                            new AttackPlayerTask(
                                    target.getUUID(), target.getName().getString()),
                            "attack_player");
        });
        h.runAfterDelay(600, () -> {
            h.assertTrue(
                    target.getHealth() < target.getMaxHealth(),
                    "target health " + target.getHealth() + ", state "
                            + c.combatController().state()
                            + ", task " + notices.finalStatus + " " + notices.finalSummary
                            + ", owner " + c.owner().isPresent()
                            + ", probe " + probe);
            h.assertTrue(notices.finalStatus != TaskStatus.FAILED, "attack failed: " + notices.finalSummary);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void equipArmorAndOffhand(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        c.inventory().addItem(new ItemStack(Items.GOLDEN_HELMET));
        c.inventory().addItem(new ItemStack(Items.TORCH));
        c.taskRunner().start(new EquipTask(Items.GOLDEN_HELMET, null), "equip_item");
        h.runAfterDelay(4, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "helmet status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("now wearing minecraft:golden_helmet"),
                    "helmet summary " + notices.finalSummary);
            h.assertTrue(c.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET), "helmet not worn");
            notices.reset();
            c.taskRunner().start(new EquipTask(Items.TORCH, EquipmentSlot.OFFHAND), "equip_item");
        });
        h.runAfterDelay(8, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "torch status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("now holding minecraft:torch in the offhand"),
                    "torch summary " + notices.finalSummary);
            h.assertTrue(c.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TORCH), "torch not in the offhand");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void equipWrongSlot(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        c.inventory().addItem(new ItemStack(Items.IRON_BOOTS));
        c.taskRunner().start(new EquipTask(Items.IRON_BOOTS, EquipmentSlot.HEAD), "equip_item");
        h.runAfterDelay(4, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.FAILED,
                    "status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("minecraft:iron_boots can't be worn on head"),
                    "summary " + notices.finalSummary);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void eatTaskResults(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Notices notices = listen(c);
        c.inventory().addItem(new ItemStack(Items.BREAD));
        c.taskRunner().start(new EatTask(Items.BREAD), "eat");
        h.runAfterDelay(100, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "bread status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("ate minecraft:bread, hunger 20/20, health 20/20"),
                    "bread summary " + notices.finalSummary);
            notices.reset();
            c.taskRunner().start(new EatTask(null), "eat");
        });
        h.runAfterDelay(104, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.FAILED,
                    "no food status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("I have no food I can eat"), "no food summary " + notices.finalSummary);
            notices.reset();
            c.inventory().addItem(new ItemStack(Items.DIRT));
            c.taskRunner().start(new EatTask(Items.DIRT), "eat");
        });
        h.runAfterDelay(108, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.FAILED,
                    "dirt status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("minecraft:dirt is not food"), "dirt summary " + notices.finalSummary);
            notices.reset();
            c.inventory().addItem(new ItemStack(Items.ROTTEN_FLESH));
            c.taskRunner().start(new EatTask(Items.ROTTEN_FLESH), "eat");
        });
        h.runAfterDelay(112, () -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.FAILED,
                    "rotten flesh status " + notices.finalStatus + " " + notices.finalSummary);
            h.assertTrue(
                    notices.finalSummary.equals("I won't eat minecraft:rotten_flesh"),
                    "rotten flesh summary " + notices.finalSummary);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void avoidCombatDoesNotCancelOwnerOrderedAttack(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        ServerPlayer target = GameTestSupport.mockPlayerAt(h, 5, 2, 2);
        target.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatSettings(c.combatSettings().withProtection(dev.friendai.combat.ProtectionPolicy.AVOID_COMBAT));
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        c.taskRunner()
                .start(new AttackPlayerTask(target.getUUID(), target.getName().getString()), "attack_player");
        h.runAfterDelay(20, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.FIGHTING,
                    "the owner's order was cancelled by avoid_combat: "
                            + c.combatController().state());
            h.assertTrue(
                    c.combatController().target().filter(t -> t == target).isPresent(),
                    "target was " + c.combatController().target());
            c.taskRunner().stop("test");
            target.kill(h.getLevel());
        });
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void stopCancelsOrderedAttackWithoutAutonomousReengagement(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        ServerPlayer target = GameTestSupport.mockPlayerAt(h, 5, 2, 2);
        target.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatSettings(c.combatSettings().withProtection(dev.friendai.combat.ProtectionPolicy.AVOID_COMBAT));
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        c.taskRunner()
                .start(new AttackPlayerTask(target.getUUID(), target.getName().getString()), "attack_player");
        h.runAfterDelay(
                20,
                () -> h.assertTrue(
                        c.combatController().state() == CombatController.State.FIGHTING,
                        "fight did not start: " + c.combatController().state()));
        h.runAfterDelay(30, () -> c.taskRunner().stop("test"));
        h.runAfterDelay(140, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "the companion re-engaged after stop: "
                            + c.combatController().state());
            h.assertTrue(
                    c.combatController().target().isEmpty(),
                    "target after stop: " + c.combatController().target());
            h.assertTrue(
                    c.taskRunner()
                            .current()
                            .map(task -> task.type())
                            .filter(type -> type.equals("attack_player"))
                            .isEmpty(),
                    "the ordered attack survived stop: "
                            + c.taskRunner().current().map(task -> task.type()));
            h.assertValueEqual(
                    c.combatSettings(),
                    dev.friendai.combat.CombatSettings.defaults()
                            .withProtection(dev.friendai.combat.ProtectionPolicy.AVOID_COMBAT),
                    "stop changed the protection settings");
            target.kill(h.getLevel());
            h.succeed();
        });
    }

    @GameTest
    public void perceptionDescribesProtectionAndStyle(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setCombatSettings(c.combatSettings()
                .withProtection(dev.friendai.combat.ProtectionPolicy.SELF_ONLY)
                .withStyle(dev.friendai.combat.FightingStyle.RANGED));
        var state = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer());
        String text = dev.friendai.perception.PerceptionBuilder.brief(
                        c,
                        new dev.friendai.perception.PerceptionContext(
                                state.resources(), state.settings(), state.freeTime()))
                .toPromptText();
        h.assertTrue(text.contains("combat: self_only/ranged"), "perception did not describe the policy:\n" + text);
        h.succeed();
    }

    @GameTest(maxTicks = 2400)
    public void miningResumesAfterFight(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        h.setBlock(8, 2, 2, Blocks.CRIMSON_STEM);
        h.setBlock(8, 3, 2, Blocks.CRIMSON_STEM);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 2, 2))),
                "lower crimson stem should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 3, 2))),
                "upper crimson stem should be directly visible");
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Notices notices = listen(c);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setCustomName(Component.literal("test-mining-zombie"));
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0);
        zombie.setHealth(60.0F);
        zombie.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0.1);
        roof(h, 5, 2);
        c.taskRunner()
                .start(
                        new MineBlocksTask(
                                BlockMatcher.parse("minecraft:crimson_stem").orElseThrow(), 2, 8),
                        "mine_blocks");
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(
                120,
                () -> h.assertTrue(
                        c.taskRunner().paused(),
                        "the runner must be paused during the fight, state "
                                + c.combatController().state()));
        h.succeedWhen(() -> {
            h.assertTrue(
                    notices.finalStatus == TaskStatus.SUCCESS,
                    "status " + notices.finalStatus + " " + notices.finalSummary
                            + ", cancelled " + notices.cancelled
                            + ", state " + c.combatController().state());
            h.assertTrue(notices.finalSummary.contains("mined 2"), "summary " + notices.finalSummary);
            h.assertTrue(notices.cancelled.isEmpty(), "the task must not be cancelled: " + notices.cancelled);
        });
    }

    @GameTest(maxTicks = 200)
    public void attackTaskLimitEndsFight(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer target = GameTestSupport.mockPlayerAt(h, 5, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        c.combatController().fight(target);
        h.assertTrue(
                c.combatController().state() == CombatController.State.FIGHTING,
                "the ordered fight did not start: " + c.combatController().state());

        AttackPlayerTask task =
                new AttackPlayerTask(target.getUUID(), target.getName().getString());
        TaskContext ctx = new TaskContext(c, h.getLevel());
        task.start(ctx);
        TaskStatus status = null;
        for (int i = 0; i < 1201; i++) {
            status = task.tick(ctx);
        }
        h.assertTrue(status == TaskStatus.FAILED, "status was " + status);
        h.assertTrue(
                task.resultSummary().equals(target.getName().getString() + " got away"),
                "summary was " + task.resultSummary());
        h.assertTrue(
                c.combatController().state() == CombatController.State.IDLE,
                "the fight must end when the task does: " + c.combatController().state());
        h.assertTrue(c.getMainHandItem().isEmpty(), "main hand not restored: " + c.getMainHandItem());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void visibleUnreachableTargetIsDropped(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        glassPen(h, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 2, 2);
        zombie.setCustomName(Component.literal("test-window-zombie"));
        roof(h, 6, 2);
        Notices notices = listen(c);
        h.runAfterDelay(
                10,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0F));
        h.runAfterDelay(
                40,
                () -> h.assertTrue(
                        c.hasLineOfSight(zombie),
                        "the fence must give line of sight, eyes " + c.getEyePosition() + " -> "
                                + zombie.getEyePosition()));
        boolean[] started = {false};
        h.succeedWhen(() -> {
            if (c.combatController().state() == CombatController.State.FIGHTING) {
                started[0] = true;
            }
            if (started[0]) {
                h.assertTrue(
                        c.combatController().state() == CombatController.State.IDLE,
                        "a visible but unreachable target must be dropped, state "
                                + c.combatController().state()
                                + ", distance " + c.distanceTo(zombie)
                                + ", notices " + notices.texts);
                h.assertTrue(!c.taskRunner().paused(), "the runner must be unpaused");
            }
        });
    }

    /** A one-block glass cell with a head-height window: visible, but the mob cannot walk out. */
    private static void glassPen(GameTestHelper h, int cx, int cz) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                for (int y = 2; y <= 4; y++) {
                    if (x != cx || z != cz) {
                        h.setBlock(x, y, z, Blocks.GLASS);
                    }
                }
            }
        }
        h.setBlock(cx + 1, 3, cz, Blocks.AIR);
    }

    /** A stone roof so undead mobs do not burn in daylight during the test. */
    private static void roof(GameTestHelper h, int cx, int cz) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                h.setBlock(x, 6, z, Blocks.STONE);
            }
        }
    }

    /** A closed glass shell with a two-block air pocket inside. */
    private static void boxIn(GameTestHelper h, int cx, int cy, int cz) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                for (int y = cy; y <= cy + 2; y++) {
                    boolean inside = x == cx && z == cz && y <= cy + 1;
                    if (!inside) {
                        h.setBlock(x, y, z, Blocks.GLASS);
                    }
                }
            }
        }
    }
}
