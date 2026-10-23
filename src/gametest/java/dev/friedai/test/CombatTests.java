package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Survival and combat behaviour. Every test spawns its own mobs in its own area. */
public class CombatTests {
    private static void assertClose(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, what + " was " + actual + ", expected " + expected);
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
}
