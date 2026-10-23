package dev.friedai.crafting.obtain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Where each overworld ore generates, keyed by the item it produces. Exploration digs to this Y; an item
 * without an entry is not explorable (emerald is mountains only, modded ores are unknown).
 */
public final class OreDepths {
    private static final Map<String, Integer> DEPTHS = depths();

    private OreDepths() {}

    /** The target Y for the item, or empty when no depth exploration knows where to go. */
    public static OptionalInt depthFor(String item) {
        if (item == null) {
            return OptionalInt.empty();
        }
        Integer depth = DEPTHS.get(item);
        return depth == null ? OptionalInt.empty() : OptionalInt.of(depth);
    }

    private static Map<String, Integer> depths() {
        Map<String, Integer> depths = new LinkedHashMap<>();
        depths.put("minecraft:coal", 96);
        depths.put("minecraft:raw_copper", 48);
        depths.put("minecraft:raw_iron", 16);
        depths.put("minecraft:lapis_lazuli", 0);
        depths.put("minecraft:raw_gold", -16);
        depths.put("minecraft:redstone", -58);
        depths.put("minecraft:diamond", -58);
        return Map.copyOf(depths);
    }
}
