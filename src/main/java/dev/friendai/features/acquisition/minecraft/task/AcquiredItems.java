package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * What the companion already carries for an acquisition: one item's count and the whole bag as a map by item id. A
 * stack in the main hand counts as held, so a tool swapped out for the work is not gathered a second time.
 */
final class AcquiredItems {
    private AcquiredItems() {}

    static int have(CompanionEntity companion, String item) {
        ItemMatcher matcher =
                ItemMatcher.parse(item).orElseThrow(() -> new IllegalArgumentException("unknown item: " + item));
        int held = matcher.test(companion.getMainHandItem())
                ? companion.getMainHandItem().getCount()
                : 0;
        return InventoryOps.count(companion.inventory(), matcher) + held;
    }

    static Map<String, Integer> inventoryMap(CompanionEntity companion) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            account(items, companion.inventory().getItem(slot));
        }
        account(items, companion.getMainHandItem());
        return items;
    }

    private static void account(Map<String, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
}
