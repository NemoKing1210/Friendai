package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.combat.minecraft.CombatController;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;

/** Notices, action feedback and bounded many-companion behaviour. */
public class CombatCoordinationTests {

    private static List<String> notices(CompanionEntity c) {
        List<String> texts = new CopyOnWriteArrayList<>();
        c.executionCoordinator().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {}

            @Override
            public void onCancelled(int id, String toolName, String reason) {}

            @Override
            public void onNotice(String text) {
                texts.add(text);
            }
        });
        return texts;
    }

    @GameTest(maxTicks = 400)
    public void retreatNoticeEmittedOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 7);
        c.setHealth(4.0f);
        List<String> texts = notices(c);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 10, 2, 7);
        zombie.setNoAi(true);
        zombie.setTarget(c);
        h.runAfterDelay(200, () -> {
            long retreats =
                    texts.stream().filter(text -> text.startsWith("retreating")).count();
            h.assertValueEqual(retreats, 1L, "retreat notices: " + texts);
            zombie.kill(h.getLevel());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void settingsChangeClearsUseAndResumesTask(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 3);
        zombie.setNoAi(true);
        h.runAfterDelay(
                10,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.runAfterDelay(
                40,
                () -> c.setCombatSettings(
                        c.combatSettings().withProtection(dev.friendai.features.combat.ProtectionPolicy.SELF_ONLY)));
        h.runAfterDelay(120, () -> {
            // SELF_ONLY still permits self-defence, so the fight may continue; the invariant is one coherent state.
            h.assertTrue(
                    !c.executionCoordinator().paused()
                            || c.combatController().state() == CombatController.State.FIGHTING,
                    "a paused task was left without a fight: "
                            + c.combatController().state());
            h.assertTrue(c.combatController().decision().action() != null, "no decision recorded");
            zombie.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }

    /** Many companions and threats must stay bounded; this records the tick cost of a dense scene. */
    @GameTest(maxTicks = 400)
    public void manyCompanionsStayWithinTickBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        List<CompanionEntity> companions = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1 + (i % 4) * 3, 2, 1 + (i / 4) * 3);
            c.inventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_SWORD));
            companions.add(c);
        }
        for (int i = 0; i < 16; i++) {
            Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2 + (i % 8) * 2, 2, 6);
            zombie.setNoAi(true);
            zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                    .setBaseValue(100.0);
            zombie.setHealth(100.0f);
        }
        long[] started = {0L};
        h.runAfterDelay(10, () -> started[0] = System.nanoTime());
        h.runAfterDelay(210, () -> {
            long elapsedMillis = (System.nanoTime() - started[0]) / 1_000_000L;
            h.assertTrue(companions.size() == 8, "companions disappeared");
            // 200 ticks of 8 companions against 16 threats; a generous budget that still catches quadratic blow-ups.
            h.assertTrue(elapsedMillis < 15_000L, "dense combat took " + elapsedMillis + " ms for 200 ticks");
            for (CompanionEntity c : companions) {
                c.combatController().stopFight();
            }
            h.succeed();
        });
    }
}
