package dev.friendai.server.agent;

import dev.friendai.agent.verify.WorldSnapshot;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Reads the facts an outcome is judged by off a companion; server thread only. */
public final class OutcomeFacts {
    private OutcomeFacts() {}

    public static WorldSnapshot snapshot(CompanionEntity companion) {
        Map<String, Integer> items = new HashMap<>();
        add(items, companion.getMainHandItem());
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            add(items, companion.inventory().getItem(i));
        }
        CompanionGuardFacts facts = new CompanionGuardFacts(companion);
        facts.refresh();
        return new WorldSnapshot(items, facts.emptySlots(), facts.ownerDistance(), facts.dimension());
    }

    private static void add(Map<String, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
}
