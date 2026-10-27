package dev.friendai.task.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a companion keeps when it unloads at storage, the way a player keeps a hotbar: the best tool of each kind,
 * enough food, a few cheap blocks for bridging, and whatever the server lists in {@code autonomy.keepItems}.
 * Pure: the caller describes the bag as {@link Slot}s.
 */
public final class KeepPolicy {
    public static final int FOOD_POINTS = 20;
    public static final int BLOCKS = 32;

    private KeepPolicy() {}

    /**
     * One bag slot.
     *
     * @param tags item tags without the leading {@code #}
     * @param toolKind the tool kind name ({@code PICKAXE}, {@code SWORD}...), blank for anything else
     * @param tierOrdinal higher is better among tools of one kind
     * @param foodPoints hunger points of one item, zero when it is not food
     * @param blockPrice the bridging price of one block, negative when it is not a usable block
     */
    public record Slot(int index, String itemId, Set<String> tags, int count, String toolKind, int tierOrdinal,
                       int foodPoints, double blockPrice) {
        public Slot {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            toolKind = toolKind == null ? "" : toolKind;
        }

        boolean tool() {
            return !toolKind.isBlank() && !toolKind.equals("HAND");
        }
    }

    /** Slot index to how many items of it stay in the bag; a slot missing from the map is deposited whole. */
    public static Map<Integer, Integer> keep(List<Slot> slots, List<String> keepItems) {
        Map<Integer, Integer> keep = new HashMap<>();
        Map<String, Slot> bestTools = new HashMap<>();
        for (Slot slot : slots) {
            if (slot.tool()) {
                bestTools.merge(slot.toolKind(), slot, (a, b) -> b.tierOrdinal() > a.tierOrdinal() ? b : a);
            } else if (listed(slot, keepItems)) {
                keep.put(slot.index(), slot.count());
            }
        }
        bestTools.values().forEach(slot -> keep.put(slot.index(), slot.count()));

        List<Slot> food = new ArrayList<>(slots.stream()
                .filter(slot -> slot.foodPoints() > 0 && !keep.containsKey(slot.index()))
                .toList());
        food.sort(Comparator.comparingInt(Slot::foodPoints).reversed().thenComparingInt(Slot::index));
        int points = 0;
        for (Slot slot : food) {
            if (points >= FOOD_POINTS) {
                break;
            }
            int needed = (FOOD_POINTS - points + slot.foodPoints() - 1) / slot.foodPoints();
            int kept = Math.min(slot.count(), needed);
            keep.put(slot.index(), kept);
            points += kept * slot.foodPoints();
        }

        List<Slot> blocks = new ArrayList<>(slots.stream()
                .filter(slot -> slot.blockPrice() >= 0 && !keep.containsKey(slot.index()))
                .toList());
        blocks.sort(Comparator.comparingDouble(Slot::blockPrice).thenComparingInt(Slot::index));
        int kept = 0;
        for (Slot slot : blocks) {
            if (kept >= BLOCKS) {
                break;
            }
            int take = Math.min(slot.count(), BLOCKS - kept);
            keep.put(slot.index(), take);
            kept += take;
        }
        return keep;
    }

    private static boolean listed(Slot slot, List<String> keepItems) {
        for (String entry : keepItems) {
            if (entry.startsWith("#") ? slot.tags().contains(entry.substring(1)) : entry.equals(slot.itemId())) {
                return true;
            }
        }
        return false;
    }
}
