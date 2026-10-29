package dev.friendai.features.inventory.minecraft;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;

/** Weapons and hands: what to hold, and how hard it hits. */
public final class Equipment {
    private Equipment() {}

    /** The best weapon in the inventory, or an empty stack when nothing beats the current main hand. */
    public static ItemStack bestMeleeWeapon(CompanionEntity companion) {
        int slot = bestMeleeWeaponSlot(companion);
        return slot < 0 ? ItemStack.EMPTY : companion.inventory().getItem(slot).copy();
    }

    /**
     * Inventory slot of a weapon better than the main hand, or -1. Ties keep the current weapon.
     *
     * <p>Weapons are compared by damage per second, not by a single hit: a shovel of a higher tier can hit harder
     * than a sword but swings much slower, and a player would still fight with the sword.
     */
    public static int bestMeleeWeaponSlot(CompanionEntity companion) {
        ItemStack mainHand = companion.getMainHandItem();
        double bare = meleeDps(companion, ItemStack.EMPTY);
        double bestDps = mainHand.isEmpty() || !usable(mainHand) ? bare : meleeDps(companion, mainHand);
        int bestSlot = -1;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (!usable(stack) || modifier(stack, Attributes.ATTACK_DAMAGE) <= 0.0) {
                continue;
            }
            double dps = meleeDps(companion, stack);
            if (dps > bestDps) {
                bestDps = dps;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** A stack with one point of durability left is not a fighting option. */
    public static boolean usable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.isDamageableItem()) {
            return true;
        }
        return stack.getMaxDamage() - stack.getDamageValue() > 1;
    }

    /**
     * Damage per second when {@code stack} is held, using the companion's own base attributes. A held item's
     * modifier replaces the bare-hand one, so the base value is the right starting point.
     */
    public static double meleeDps(CompanionEntity companion, ItemStack stack) {
        double damage =
                companion.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) + modifier(stack, Attributes.ATTACK_DAMAGE);
        double speed =
                companion.getAttributeBaseValue(Attributes.ATTACK_SPEED) + modifier(stack, Attributes.ATTACK_SPEED);
        return Math.max(0.0, damage) * Math.max(0.0, speed);
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

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** A cheap fingerprint of the armor-capable inventory stacks, for the periodic check. */
    public static int armorHash(CompanionEntity companion) {
        int hash = 1;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.slot().isArmor()) {
                hash = 31 * hash + stack.hashCode();
            }
        }
        return hash;
    }

    /**
     * Wears a strictly better piece in each armor slot. Equal pieces are left alone, and a worn piece with
     * Curse of Binding is never removed.
     */
    public static void equipBestArmor(CompanionEntity companion) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack worn = companion.getItemBySlot(slot);
            if (hasBindingCurse(companion, worn)) {
                continue;
            }
            double bestArmor = armor(worn, slot);
            double bestToughness = toughness(worn, slot);
            int bestSlot = -1;
            for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
                ItemStack stack = companion.inventory().getItem(i);
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot) {
                    continue;
                }
                double candidateArmor = armor(stack, slot);
                double candidateToughness = toughness(stack, slot);
                if (candidateArmor > bestArmor || (candidateArmor == bestArmor && candidateToughness > bestToughness)) {
                    bestArmor = candidateArmor;
                    bestToughness = candidateToughness;
                    bestSlot = i;
                }
            }
            if (bestSlot >= 0) {
                ItemStack better = companion.inventory().removeItemNoUpdate(bestSlot);
                companion.setItemSlot(slot, better);
                if (!worn.isEmpty()) {
                    ItemStack leftover = companion.inventory().addItem(worn);
                    if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                        companion.spawnAtLocation(level, leftover);
                    }
                }
            }
        }
    }

    private static boolean hasBindingCurse(CompanionEntity companion, ItemStack stack) {
        if (stack.isEmpty() || !(companion.level() instanceof ServerLevel level)) {
            return false;
        }
        return EnchantmentHelper.getItemEnchantmentLevel(
                        level.registryAccess()
                                .lookupOrThrow(Registries.ENCHANTMENT)
                                .getOrThrow(Enchantments.BINDING_CURSE),
                        stack)
                > 0;
    }

    private static double armor(ItemStack stack, EquipmentSlot slot) {
        return modifier(stack, Attributes.ARMOR, slot);
    }

    private static double toughness(ItemStack stack, EquipmentSlot slot) {
        return modifier(stack, Attributes.ARMOR_TOUGHNESS, slot);
    }

    /** The value an item's main-hand modifiers add to an attribute. */
    private static double modifier(ItemStack stack, Holder<Attribute> attribute) {
        return modifier(stack, attribute, EquipmentSlot.MAINHAND);
    }

    /** The value an item's modifiers for a slot add to an attribute. */
    private static double modifier(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? 0.0 : modifiers.compute(attribute, 0.0, slot);
    }
}
