package dev.friendai.features.inventory.minecraft.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.inventory.ItemMatcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/**
 * Checkpoint decoding for {@link MoveItemsTask}, contributed beside its definition in {@code StorageTools}. Only a
 * transfer whose withdrawal already committed is restorable: repeating that request would take the items twice.
 *
 * <p>Version 2 records the withdrawn stacks themselves (item, components and the count each receipt covers), so a
 * restore deposits the transferred items rather than anything the bag happens to match. Version 1 saves have no
 * receipts and keep the old matcher behavior for the count that is left.
 */
public final class MoveItemsCheckpoints {
    static final String VERSION = "v2";
    /** A double chest has 54 slots; one receipt is one withdrawn portion and never exceeds a real stack. */
    static final int MAX_RECEIPTS = 54;

    static final int MAX_RECEIPTS_CHARS = 3_000;

    private MoveItemsCheckpoints() {}

    /**
     * Serializes a committed withdrawal. Falls back to the v1 shape when the receipts cannot be written (too many, too
     * large, or no registry access), so the transfer is always restorable even though it loses item identity then.
     */
    public static String encode(
            ItemMatcher matcher,
            int count,
            BlockPos from,
            BlockPos to,
            MoveItemsTask.Phase phase,
            int moved,
            int inserted,
            int returned,
            int leftover,
            List<ItemStack> receipts,
            int[] delivered,
            HolderLookup.Provider registries) {
        if (from == null || to == null || phase == null) return null;
        String current = currentRecord(
                matcher, count, from, to, phase, moved, inserted, returned, leftover, receipts, delivered, registries);
        if (current != null) return current;
        return legacyRecord(matcher, count, from, to, phase, moved, inserted, Math.max(0, moved - inserted));
    }

    private static String currentRecord(
            ItemMatcher matcher,
            int count,
            BlockPos from,
            BlockPos to,
            MoveItemsTask.Phase phase,
            int moved,
            int inserted,
            int returned,
            int leftover,
            List<ItemStack> receipts,
            int[] delivered,
            HolderLookup.Provider registries) {
        if (receipts == null || receipts.isEmpty() || registries == null) return null;
        JsonArray encoded = new JsonArray();
        for (ItemStack receipt : receipts) {
            DataResult<JsonElement> json = ItemStack.CODEC.encodeStart(ops(registries), receipt);
            JsonElement value = json.result().orElse(null);
            if (value == null) return null;
            encoded.add(value);
        }
        if (encoded.size() > MAX_RECEIPTS) return null;
        String receiptsText = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(encoded.toString().getBytes(StandardCharsets.UTF_8));
        if (receiptsText.length() > MAX_RECEIPTS_CHARS) return null;
        JsonArray deliveredText = new JsonArray();
        for (int i = 0; i < encoded.size(); i++) deliveredText.add(i < delivered.length ? delivered[i] : 0);
        return VERSION + "|" + matcher.label() + "|" + count + "|" + from.asLong() + "|" + to.asLong() + "|"
                + phase.name() + "|" + moved + "|" + inserted + "|" + returned + "|" + leftover + "|" + receiptsText
                + "|" + deliveredText;
    }

    private static String legacyRecord(
            ItemMatcher matcher,
            int count,
            BlockPos from,
            BlockPos to,
            MoveItemsTask.Phase phase,
            int moved,
            int inserted,
            int leftover) {
        return "v1|" + matcher.label() + "|" + count + "|" + from.asLong() + "|" + to.asLong() + "|" + phase.name()
                + "|" + moved + "|" + inserted + "|" + leftover;
    }

    public static Optional<Task> move(TaskState state, TaskContext context) {
        try {
            String[] parts = state.data().split("\\|", -1);
            if (parts.length == 9 && "v1".equals(parts[0])) return legacy(parts);
            if (parts.length == 12 && VERSION.equals(parts[0])) return current(parts, context);
            return Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<Task> legacy(String[] parts) {
        String label = parts[1];
        int count = Integer.parseInt(parts[2]);
        BlockPos from = parts[3].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[3]));
        BlockPos to = parts[4].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[4]));
        boolean storing = "STORE".equals(parts[5]);
        boolean returning = "RETURN".equals(parts[5]);
        int moved = Integer.parseInt(parts[6]);
        int inserted = Integer.parseInt(parts[7]);
        int leftover = Integer.parseInt(parts[8]);
        if (from == null
                || to == null
                || from.equals(to)
                || (!storing && !returning)
                || count < 1
                || moved < 1
                || moved > count
                || inserted < 0
                || inserted > moved
                || leftover < 0
                || leftover != moved - inserted) {
            return Optional.empty();
        }
        Optional<ItemMatcher> matcher = ItemMatcher.parse(label);
        if (matcher.isEmpty()) return Optional.empty();
        MoveItemsTask.Phase phase = storing ? MoveItemsTask.Phase.STORE : MoveItemsTask.Phase.RETURN;
        return Optional.of(MoveItemsTask.resumed(
                matcher.get(), count, from, to, phase, moved, inserted, 0, leftover, List.of(), new int[0]));
    }

    private static Optional<Task> current(String[] parts, TaskContext context) {
        String label = parts[1];
        int count = Integer.parseInt(parts[2]);
        BlockPos from = parts[3].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[3]));
        BlockPos to = parts[4].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[4]));
        boolean storing = "STORE".equals(parts[5]);
        boolean returning = "RETURN".equals(parts[5]);
        int moved = Integer.parseInt(parts[6]);
        int inserted = Integer.parseInt(parts[7]);
        int returned = Integer.parseInt(parts[8]);
        int leftover = Integer.parseInt(parts[9]);
        if (from == null
                || to == null
                || from.equals(to)
                || (!storing && !returning)
                || count < 1
                || moved < 1
                || moved > count
                || inserted < 0
                || returned < 0
                || leftover < 0
                || inserted + returned + leftover > moved) {
            return Optional.empty();
        }
        HolderLookup.Provider registries = registries(context);
        if (registries == null) return Optional.empty();
        List<ItemStack> receipts = decodeReceipts(parts[10], registries);
        if (receipts == null || receipts.isEmpty()) return Optional.empty();
        int[] delivered = decodeDelivered(parts[11], receipts.size());
        if (delivered == null) return Optional.empty();
        int total = 0, accepted = 0;
        for (int i = 0; i < receipts.size(); i++) {
            total += receipts.get(i).getCount();
            accepted += delivered[i];
            if (delivered[i] > receipts.get(i).getCount()) return Optional.empty();
        }
        if (total != moved || accepted != inserted) return Optional.empty();
        Optional<ItemMatcher> matcher = ItemMatcher.parse(label);
        if (matcher.isEmpty()) return Optional.empty();
        MoveItemsTask.Phase phase = storing ? MoveItemsTask.Phase.STORE : MoveItemsTask.Phase.RETURN;
        return Optional.of(MoveItemsTask.resumed(
                matcher.get(), count, from, to, phase, moved, inserted, returned, leftover, receipts, delivered));
    }

    private static List<ItemStack> decodeReceipts(String text, HolderLookup.Provider registries) {
        if (text.isEmpty()) return null;
        String json = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) return null;
        JsonArray array = parsed.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_RECEIPTS) return null;
        List<ItemStack> receipts = new ArrayList<>();
        for (JsonElement element : array) {
            ItemStack stack =
                    ItemStack.CODEC.parse(ops(registries), element).result().orElse(null);
            if (stack == null || stack.isEmpty()) return null;
            receipts.add(stack);
        }
        return receipts;
    }

    private static int[] decodeDelivered(String text, int size) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() != size) return null;
        int[] delivered = new int[size];
        for (int i = 0; i < size; i++) {
            delivered[i] = parsed.getAsJsonArray().get(i).getAsInt();
            if (delivered[i] < 0) return null;
        }
        return delivered;
    }

    private static HolderLookup.Provider registries(TaskContext context) {
        if (context == null || context.level() == null) return null;
        return context.level().registryAccess();
    }

    private static RegistryOps<JsonElement> ops(HolderLookup.Provider registries) {
        return RegistryOps.create(JsonOps.INSTANCE, registries);
    }
}
