package dev.friendai.perception;

import dev.friendai.survival.HungerModel;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** What the companion knows about its surroundings, with no Minecraft types. Text is English, LLM-facing. */
public record PerceptionSnapshot(
        String position,
        String dimension,
        float health,
        float maxHealth,
        int food,
        String timeOfDay,
        String biome,
        String weather,
        Double ownerDistance,
        Map<String, Integer> nearbyBlocks,
        Map<String, Integer> nearbyEntities,
        Map<String, Integer> inventory,
        String equipment,
        String currentTask,
        OwnerFocus ownerFocus,
        SelfStatus self,
        List<Threat> threats,
        List<Landmark> landmarks) {

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Position: ").append(position).append(" (").append(dimension).append(")\n");
        if (self != null) {
            sb.append(self.toPromptText()).append('\n');
        }
        sb.append("Health: ").append(number(health)).append('/').append(number(maxHealth)).append('\n');
        sb.append("Hunger: ").append(food).append('/').append(HungerModel.MAX_FOOD).append('\n');
        sb.append("Time: ").append(timeOfDay).append(", weather: ").append(weather).append(", biome: ").append(biome).append('\n');
        if (ownerFocus != null) {
            sb.append(ownerFocus.toPromptText()).append('\n');
        } else {
            sb.append("Owner: ")
                    .append(ownerDistance == null ? "not nearby" : String.format(Locale.ROOT, "%.1f blocks away", ownerDistance))
                    .append('\n');
        }
        if (threats != null) {
            sb.append("Threats: ")
                    .append(threats.isEmpty()
                            ? "none"
                            : threats.stream().map(Threat::toPromptText).collect(Collectors.joining("; ")))
                    .append('\n');
        }
        if (landmarks != null) {
            sb.append("Landmarks: ")
                    .append(landmarks.isEmpty()
                            ? "none nearby"
                            : landmarks.stream().map(Landmark::toPromptText).collect(Collectors.joining("; ")))
                    .append('\n');
        }
        sb.append("Nearby blocks: ").append(counts(nearbyBlocks, "none")).append('\n');
        sb.append("Nearby entities: ").append(counts(nearbyEntities, "none")).append('\n');
        sb.append("Inventory: ").append(counts(inventory, "empty")).append('\n');
        sb.append(equipment).append('\n');
        sb.append("Current task: ").append(currentTask);
        return sb.toString();
    }

    /** A whole number without a decimal point, otherwise one decimal, as the prompts want it. */
    public static String number(float value) {
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
