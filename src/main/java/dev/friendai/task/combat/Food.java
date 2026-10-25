package dev.friendai.task.combat;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.survival.HungerModel;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Which food the companion may eat, which one is best, and the owner's rules that override the defaults. */
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

    /**
     * Safe food heals; golden apples are kept for emergencies. An explicit rule from the owner wins over
     * both: an allowed food is eaten even when it is risky, a forbidden one is never eaten.
     */
    public static boolean isSafe(CompanionEntity companion, ItemStack stack, boolean goldenAllowed) {
        if (stack.isEmpty() || stack.get(DataComponents.FOOD) == null) {
            return false;
        }
        Optional<Boolean> rule = companion.foodRule(id(stack.getItem()));
        if (rule.isPresent()) {
            return rule.get();
        }
        if (UNSAFE.contains(stack.getItem())) {
            return false;
        }
        return goldenAllowed || !GOLDEN.contains(stack.getItem());
    }

    /** Item ids the companion refuses by default; the owner can allow them one by one. */
    public static List<String> defaultForbiddenIds() {
        return UNSAFE.stream().map(Food::id).sorted().toList();
    }

    /** Item ids kept for emergencies unless the owner allows them. */
    public static List<String> emergencyIds() {
        return GOLDEN.stream().map(Food::id).sorted().toList();
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * The inventory slot of the best safe food for the current hunger: the highest nutrition that
     * fits in the remaining bar ({@code 20 - food}); when nothing fits, the smallest nutrition.
     * Ties go to the higher saturation, then to the smallest stack.
     */
    public static OptionalInt choose(CompanionEntity companion, boolean goldenAllowed) {
        int room = HungerModel.MAX_FOOD - companion.hunger().food();
        int bestSlot = -1;
        FoodProperties bestFood = null;
        int bestCount = 0;
        boolean bestFits = false;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (!isSafe(companion, stack, goldenAllowed)) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            boolean fits = food.nutrition() <= room;
            boolean better;
            if (bestFood == null) {
                better = true;
            } else if (fits != bestFits) {
                better = fits;
            } else if (fits) {
                better = food.nutrition() > bestFood.nutrition()
                        || (food.nutrition() == bestFood.nutrition() && food.saturation() > bestFood.saturation())
                        || (food.nutrition() == bestFood.nutrition()
                                && food.saturation() == bestFood.saturation()
                                && stack.getCount() < bestCount);
            } else {
                better = food.nutrition() < bestFood.nutrition()
                        || (food.nutrition() == bestFood.nutrition() && food.saturation() > bestFood.saturation())
                        || (food.nutrition() == bestFood.nutrition()
                                && food.saturation() == bestFood.saturation()
                                && stack.getCount() < bestCount);
            }
            if (better) {
                bestSlot = i;
                bestFood = food;
                bestCount = stack.getCount();
                bestFits = fits;
            }
        }
        return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
    }
}
