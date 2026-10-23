package dev.friedai.task.combat;

import dev.friedai.entity.CompanionEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Weapons and hands: what to hold, and how hard it hits. */
public final class Equipment {
    private Equipment() {}

    /** The best weapon in the inventory, or an empty stack when nothing beats the current main hand. */
    public static ItemStack bestMeleeWeapon(CompanionEntity companion) {
        int slot = bestMeleeWeaponSlot(companion);
        return slot < 0 ? ItemStack.EMPTY : companion.inventory().getItem(slot).copy();
    }

    /** Inventory slot of a weapon better than the main hand, or -1. Ties keep the current weapon. */
    public static int bestMeleeWeaponSlot(CompanionEntity companion) {
        double bestDamage = modifier(companion.getMainHandItem(), Attributes.ATTACK_DAMAGE);
        double bestSpeed = modifier(companion.getMainHandItem(), Attributes.ATTACK_SPEED);
        int bestSlot = -1;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            double damage = modifier(stack, Attributes.ATTACK_DAMAGE);
            double speed = modifier(stack, Attributes.ATTACK_SPEED);
            if (damage > bestDamage || (damage == bestDamage && speed > bestSpeed)) {
                bestDamage = damage;
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** Moves the inventory stack to the main hand and puts the previous one back in the inventory. */
    public static void hold(CompanionEntity companion, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= companion.inventory().getContainerSize()) {
            return;
        }
        ItemStack stack = companion.inventory().removeItemNoUpdate(inventorySlot);
        if (stack.isEmpty()) {
            return;
        }
        ItemStack previous = companion.getMainHandItem();
        companion.setItemSlot(EquipmentSlot.MAINHAND, stack);
        if (!previous.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(previous);
            if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                companion.spawnAtLocation(level, leftover);
            }
        }
    }

    public static boolean hasBowAndArrows(CompanionEntity companion) {
        boolean bow = companion.getMainHandItem().is(Items.BOW);
        for (int i = 0; i < companion.inventory().getContainerSize() && !bow; i++) {
            bow = companion.inventory().getItem(i).is(Items.BOW);
        }
        return bow && companion.inventory().countItem(Items.ARROW) > 0;
    }

    /** The value an item's main-hand modifiers add to an attribute. */
    private static double modifier(ItemStack stack, Holder<Attribute> attribute) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? 0.0 : modifiers.compute(attribute, 0.0, EquipmentSlot.MAINHAND);
    }
}
