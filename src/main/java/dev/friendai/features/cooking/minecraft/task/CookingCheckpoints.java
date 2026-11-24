package dev.friendai.features.cooking.minecraft.task;

import dev.friendai.features.cooking.minecraft.CookingWait;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.StationPolicy;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Checkpoint decoding for {@link SmeltTask}, contributed beside its definition in {@code CookingTools}. Only a
 * waiting batch restores: its inputs are already committed to the stations, so repeating the request would load a
 * second batch. Everything before the wait is planned again from the world.
 */
public final class CookingCheckpoints {
    private static final String VERSION = "v1";
    private static final int FIELDS = 20;

    private CookingCheckpoints() {}

    /**
     * One id or {@code #tag} becomes the same matcher the tool built; a comma list is a multi-item batch (cooked food)
     * and is rebuilt as the set it was saved from.
     */
    private static Optional<ItemMatcher> parseInput(String saved) {
        if (saved.isEmpty()) return Optional.empty();
        if (!saved.contains(",")) return ItemMatcher.parse(saved);
        Set<String> ids = new LinkedHashSet<>();
        for (String id : saved.split(",", -1)) {
            if (id.isEmpty()
                    || Identifier.tryParse(id) == null
                    || !BuiltInRegistries.ITEM.containsKey(Identifier.parse(id))) {
                return Optional.empty();
            }
            ids.add(id);
        }
        return ids.isEmpty() ? Optional.empty() : Optional.of(new SmeltTask.SetMatcher(Set.copyOf(ids)));
    }

    /** @param factory the runtime's ingredient source, so a restored batch can still place a station it needs */
    public static Optional<Task> smelt(TaskState state, TaskContext context) {
        return smelt(state, context, CraftingTaskFactory.UNAVAILABLE);
    }

    public static Optional<Task> smelt(TaskState state, TaskContext context, CraftingTaskFactory factory) {
        try {
            String[] parts = state.data().split("\\|", -1);
            if (parts.length != FIELDS || !VERSION.equals(parts[0])) {
                return Optional.empty();
            }
            Optional<ItemMatcher> input = parseInput(parts[1]);
            int count = Integer.parseInt(parts[2]);
            CookingWait waitRequest = CookingWait.valueOf(parts[3]);
            int requested = Integer.parseInt(parts[4]);
            int loaded = Integer.parseInt(parts[5]);
            int stations = Integer.parseInt(parts[6]);
            int collected = Integer.parseInt(parts[7]);
            int waitTicks = Integer.parseInt(parts[8]);
            int unstarted = Integer.parseInt(parts[9]);
            Station kind = Station.valueOf(parts[10]);
            StationPolicy policy = StationPolicy.valueOf(parts[11]);
            int waitLimit = Integer.parseInt(parts[12]);
            Optional<ItemMatcher> fuel = parts[13].isEmpty() ? Optional.empty() : ItemMatcher.parse(parts[13]);
            String unstartedReason = parts[14].isEmpty() ? null : parts[14];
            String fuelNote = parts[15].isEmpty() ? null : parts[15];
            String outputId = parts[16].isEmpty() ? null : parts[16];
            // A waiting batch has already chosen its station and loaded its inputs, so the result is fixed by what
            // is in the furnace; the expected output only guides planning and is not restored.
            if (input.isEmpty()
                    || (parts[13].isEmpty() != fuel.isEmpty())
                    || waitRequest != CookingWait.WAIT
                    || !kind.isCooking()
                    || count < 1
                    || requested < 1
                    || loaded < 0
                    || loaded > requested
                    || stations < 1
                    || stations > SmeltTask.MAX_FURNACES
                    || collected < 0
                    || waitTicks < 0
                    || unstarted < 0) {
                return Optional.empty();
            }
            Set<String> expected = new LinkedHashSet<>();
            if (!parts[17].isEmpty()) {
                for (String id : parts[17].split(";")) {
                    if (Identifier.tryParse(id) == null) return Optional.empty();
                    expected.add(id);
                }
            }
            List<ItemStack> waiting = new ArrayList<>();
            if (!parts[18].isEmpty()) {
                for (String entry : parts[18].split(";")) {
                    String[] fields = entry.split(",", -1);
                    if (fields.length != 2 || Identifier.tryParse(fields[0]) == null) return Optional.empty();
                    Identifier id = Identifier.parse(fields[0]);
                    if (!BuiltInRegistries.ITEM.containsKey(id)) return Optional.empty();
                    int amount = Integer.parseInt(fields[1]);
                    if (amount < 1) return Optional.empty();
                    waiting.add(new ItemStack(BuiltInRegistries.ITEM.getValue(id), amount));
                }
            }
            List<SmeltTask.Target> targets = new ArrayList<>();
            if (!parts[19].isEmpty()) {
                for (String entry : parts[19].split(";")) {
                    String[] fields = entry.split(",", -1);
                    if (fields.length != 2) return Optional.empty();
                    BlockPos pos = BlockPos.of(Long.parseLong(fields[0]));
                    if (!"true".equals(fields[1]) && !"false".equals(fields[1])) return Optional.empty();
                    if (!(context.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                        return Optional.empty();
                    }
                    targets.add(new SmeltTask.Target(pos, furnace, Boolean.parseBoolean(fields[1])));
                }
            }
            if (targets.isEmpty() || targets.size() != stations) return Optional.empty();
            return Optional.of(SmeltTask.resumed(
                    input.get(),
                    count,
                    waitRequest,
                    fuel.orElse(null),
                    policy,
                    waitLimit,
                    factory,
                    kind,
                    requested,
                    loaded,
                    stations,
                    collected,
                    waitTicks,
                    unstarted,
                    unstartedReason,
                    fuelNote,
                    outputId,
                    List.copyOf(expected),
                    waiting,
                    targets));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
