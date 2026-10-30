package dev.friendai.features.survival.minecraft.selfcare;

import dev.friendai.features.survival.selfcare.EffectMark;
import dev.friendai.features.survival.selfcare.SelfCareEffect;
import dev.friendai.features.survival.selfcare.SelfCareItem;
import dev.friendai.features.survival.selfcare.SelfCareStock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jetbrains.annotations.Nullable;

/** Reads the self-care facts out of real stacks: what a slot holds for an emergency and what an effect does. */
public final class SelfCareItems {
    private SelfCareItems() {}

    /** What {@code stack} is worth to the self-care layer, or null when it is not one of its items. */
    public static @Nullable SelfCareItem kindOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item == Items.WATER_BUCKET) {
            return SelfCareItem.WATER_BUCKET;
        }
        if (item == Items.BUCKET) {
            return SelfCareItem.EMPTY_BUCKET;
        }
        if (item == Items.MILK_BUCKET) {
            return SelfCareItem.MILK_BUCKET;
        }
        if (item == Items.GOLDEN_APPLE) {
            return SelfCareItem.GOLDEN_APPLE;
        }
        if (item == Items.ENCHANTED_GOLDEN_APPLE) {
            return SelfCareItem.ENCHANTED_GOLDEN_APPLE;
        }
        if (item == Items.POTION || item == Items.SPLASH_POTION) {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents == null ? null : potionKind(contents, item == Items.SPLASH_POTION);
        }
        return null;
    }

    /** How many of each emergency item the container holds. */
    public static SelfCareStock stock(Container inventory) {
        int[] counts = new int[SelfCareItem.values().length];
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            SelfCareItem kind = kindOf(stack);
            if (kind != null) {
                counts[kind.ordinal()] += stack.getCount();
            }
        }
        return new SelfCareStock(
                counts[SelfCareItem.WATER_BUCKET.ordinal()],
                counts[SelfCareItem.EMPTY_BUCKET.ordinal()],
                counts[SelfCareItem.MILK_BUCKET.ordinal()],
                counts[SelfCareItem.GOLDEN_APPLE.ordinal()],
                counts[SelfCareItem.ENCHANTED_GOLDEN_APPLE.ordinal()],
                counts[SelfCareItem.POTION_HEALING.ordinal()],
                counts[SelfCareItem.POTION_REGENERATION.ordinal()],
                counts[SelfCareItem.POTION_FIRE_RESISTANCE.ordinal()],
                counts[SelfCareItem.POTION_WATER_BREATHING.ordinal()],
                counts[SelfCareItem.POTION_SLOW_FALLING.ordinal()],
                counts[SelfCareItem.SPLASH_POTION_HEALING.ordinal()]);
    }

    /** Takes one item of {@code kind} out of the container, or an empty stack when it carries none. */
    public static ItemStack take(Container inventory, SelfCareItem kind) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (kindOf(stack) != kind) {
                continue;
            }
            ItemStack taken = stack.copyWithCount(1);
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
            inventory.setChanged();
            return taken;
        }
        return ItemStack.EMPTY;
    }

    /** The body's active effects, each already marked harmful or beneficial by the effect itself. */
    public static List<SelfCareEffect> effects(LivingEntity entity) {
        List<SelfCareEffect> effects = new ArrayList<>();
        for (MobEffectInstance instance : entity.getActiveEffects()) {
            MobEffect effect = instance.getEffect().value();
            effects.add(new SelfCareEffect(
                    instance.getEffect().getRegisteredName(),
                    instance.getAmplifier(),
                    instance.getDuration(),
                    mark(effect)));
        }
        return List.copyOf(effects);
    }

    /**
     * A potion is worth keeping only when it does what the self-care layer asks: a splash potion must be entirely
     * beneficial and heal, and a drinkable one must be a heal, regeneration or one of the buffs.
     */
    private static @Nullable SelfCareItem potionKind(PotionContents contents, boolean splash) {
        boolean harmful = false;
        boolean beneficial = false;
        boolean heals = false;
        boolean regenerates = false;
        boolean fire = false;
        boolean breath = false;
        boolean slow = false;
        for (MobEffectInstance instance : contents.getAllEffects()) {
            MobEffect effect = instance.getEffect().value();
            if (effect.getCategory() == MobEffectCategory.HARMFUL) {
                harmful = true;
            } else if (effect.getCategory() == MobEffectCategory.BENEFICIAL) {
                beneficial = true;
            }
            heals |= instance.getEffect().is(MobEffects.INSTANT_HEALTH);
            regenerates |= instance.getEffect().is(MobEffects.REGENERATION);
            fire |= instance.getEffect().is(MobEffects.FIRE_RESISTANCE);
            breath |= instance.getEffect().is(MobEffects.WATER_BREATHING);
            slow |= instance.getEffect().is(MobEffects.SLOW_FALLING);
        }
        if (splash) {
            return !harmful && beneficial && (heals || regenerates) ? SelfCareItem.SPLASH_POTION_HEALING : null;
        }
        if (harmful) {
            return null;
        }
        if (heals) {
            return SelfCareItem.POTION_HEALING;
        }
        if (regenerates) {
            return SelfCareItem.POTION_REGENERATION;
        }
        if (fire) {
            return SelfCareItem.POTION_FIRE_RESISTANCE;
        }
        if (breath) {
            return SelfCareItem.POTION_WATER_BREATHING;
        }
        if (slow) {
            return SelfCareItem.POTION_SLOW_FALLING;
        }
        return null;
    }

    private static EffectMark mark(MobEffect effect) {
        if (effect.getCategory() == MobEffectCategory.HARMFUL) {
            return EffectMark.HARMFUL;
        }
        return effect.getCategory() == MobEffectCategory.BENEFICIAL ? EffectMark.BENEFICIAL : EffectMark.NEUTRAL;
    }
}
