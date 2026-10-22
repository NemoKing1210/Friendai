package dev.friedai.perception;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** What the companion knows about its surroundings, with no Minecraft types. Text is English, LLM-facing. */
public record PerceptionSnapshot(
        String position,
        String dimension,
        float health,
        float maxHealth,
        String timeOfDay,
        String biome,
        String weather,
        Double ownerDistance,
        Map<String, Integer> nearbyBlocks,
        Map<String, Integer> nearbyEntities,
        Map<String, Integer> inventory,
        String currentTask) {

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Position: ").append(position).append(" (").append(dimension).append(")\n");
        sb.append("Health: ").append(number(health)).append('/').append(number(maxHealth)).append('\n');
        sb.append("Time: ").append(timeOfDay).append(", weather: ").append(weather).append(", biome: ").append(biome).append('\n');
        sb.append("Owner: ")
                .append(ownerDistance == null ? "not nearby" : String.format(Locale.ROOT, "%.1f blocks away", ownerDistance))
                .append('\n');
        sb.append("Nearby blocks: ").append(counts(nearbyBlocks, "none")).append('\n');
        sb.append("Nearby entities: ").append(counts(nearbyEntities, "none")).append('\n');
        sb.append("Inventory: ").append(counts(inventory, "empty")).append('\n');
        sb.append("Current task: ").append(currentTask);
        return sb.toString();
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** "name×count, …" in map order, or {@code empty} when the map has no entries. */
    public static String counts(Map<String, Integer> map, String empty) {
        if (map.isEmpty()) {
            return empty;
        }
        return map.entrySet().stream().map(e -> e.getKey() + "×" + e.getValue()).collect(Collectors.joining(", "));
    }
}
