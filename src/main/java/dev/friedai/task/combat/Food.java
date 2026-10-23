package dev.friedai.task.combat;

import dev.friedai.entity.CompanionEntity;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Which food the companion may eat, and which one is best. */
public final class Food {
    private static final Set<Item> UNSAFE = Set.of(
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.PUFFERFISH,
            Items.POISONOUS_POTATO,
            Items.CHORUS_FRUIT,
            Items.SUSPICIOUS_STEW);
    private static final Set<Item> GOLDEN = Set.of(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);

    private Food() {}

    /** Safe food heals; golden apples are kept for emergencies. */
    public static boolean isSafe(ItemStack stack, boolean goldenAllowed) {
        if (stack.isEmpty() || stack.get(DataComponents.FOOD) == null) {
            return false;
        }
        if (UNSAFE.contains(stack.getItem())) {
            return false;
        }
        return goldenAllowed || !GOLDEN.contains(stack.getItem());
    }

    /** The inventory slot of the best safe food: nutrition, then saturation, then the smallest stack. */
    public static OptionalInt best(CompanionEntity companion, boolean goldenAllowed) {
        int bestSlot = -1;
        FoodProperties bestFood = null;
        int bestCount = 0;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (!isSafe(stack, goldenAllowed)) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            boolean better = bestFood == null
                    || food.nutrition() > bestFood.nutrition()
                    || (food.nutrition() == bestFood.nutrition() && food.saturation() > bestFood.saturation())
                    || (food.nutrition() == bestFood.nutrition()
                            && food.saturation() == bestFood.saturation()
                            && stack.getCount() < bestCount);
            if (better) {
                bestSlot = i;
                bestFood = food;
                bestCount = stack.getCount();
            }
        }
        return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
    }

    /** Hit points restored by one serving, or zero when the item is not food. */
    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }
}
