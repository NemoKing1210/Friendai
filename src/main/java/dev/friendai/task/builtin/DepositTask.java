package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.MaterialPrices;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceQuery;
import dev.friendai.place.PlaceService;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.inventory.KeepPolicy;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.LevelCellView;
import dev.friendai.task.nav.MaterialPicker;
import dev.friendai.task.obtain.DropIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Unloads loot into the containers of a remembered storage place (or home), like a player sorting a base: each
 * stack first goes to a container that already holds that item, then to an empty one, then to any with room.
 * What {@link KeepPolicy} keeps stays in the bag.
 */
public final class DepositTask implements Task {
    /** How many containers of one place a deposit visits. */
    static final int MAX_CONTAINERS = 16;

    private enum Pass { SAME, ANY }

    private final String placeName;
    private final List<String> keepItems;

    private Place place;
    private List<BlockPos> containers = List.of();
    private final Set<BlockPos> emptyAtOpen = new HashSet<>();
    private final Set<BlockPos> used = new LinkedHashSet<>();
    private Pass pass = Pass.SAME;
    private int index;
    private Approach approach;
    private int moved;
    private String summary = "";

    public DepositTask(String placeName, List<String> keepItems) {
        this.placeName = placeName == null || placeName.isBlank() ? null : placeName.strip();
        this.keepItems = List.copyOf(keepItems);
    }

    @Override
    public String type() {
        return "deposit_items";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (place == null) {
            Optional<String> failure = resolve(c);
            if (failure.isPresent()) {
                summary = failure.get();
                return TaskStatus.FAILED;
            }
        }
        if (depositable(c).isEmpty()) {
            return finish(true);
        }
        if (index >= containers.size()) {
            if (pass == Pass.SAME) {
                pass = Pass.ANY;
                index = 0;
                List<BlockPos> ordered = new ArrayList<>(containers.stream().filter(emptyAtOpen::contains).toList());
                containers.stream().filter(pos -> !emptyAtOpen.contains(pos)).forEach(ordered::add);
                containers = ordered;
                return TaskStatus.RUNNING;
            }
            return finish(false);
        }
        BlockPos target = containers.get(index);
        if (approach == null) {
            approach = new Approach(target);
        }
        switch (approach.tick(c.companion())) {
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case UNREACHABLE -> {
                next(c);
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> {
                visit(c, target);
                next(c);
                return TaskStatus.RUNNING;
            }
        }
        return TaskStatus.RUNNING;
    }

    private Optional<String> resolve(TaskContext c) {
        CompanionEntity companion = c.companion();
        try {
            if (placeName != null) {
                place = PlaceService.requireReachable(companion, placeName, "deposit_items");
            } else {
                place = nearest(companion, PlaceKind.STORAGE).or(() -> nearest(companion, PlaceKind.HOUSE))
                        .orElse(null);
                if (place == null) {
                    return Optional.of("no storage place here; remember one with remember_place");
                }
            }
        } catch (IllegalArgumentException unknown) {
            return Optional.of(unknown.getMessage());
        }
        BlockPos anchor = new BlockPos(place.anchor().x(), place.anchor().y(), place.anchor().z());
        int r = place.radius();
        containers = ContainerAccess.all(c.level(), anchor, r * 2).stream()
                .filter(pos -> Math.abs(pos.getX() - anchor.getX()) <= r
                        && Math.abs(pos.getY() - anchor.getY()) <= r
                        && Math.abs(pos.getZ() - anchor.getZ()) <= r)
                .limit(MAX_CONTAINERS)
                .toList();
        if (containers.isEmpty()) {
            return Optional.of("no chest or barrel at " + place.name());
        }
        return Optional.empty();
    }

    private static Optional<Place> nearest(CompanionEntity companion, PlaceKind kind) {
        return PlaceService.find(companion, new PlaceQuery.Request(Optional.empty(), Set.of(kind), Optional.empty(),
                        1, false))
                .stream().findFirst().map(PlaceQuery.Result::place);
    }

    private void visit(TaskContext c, BlockPos pos) {
        ServerLevel level = c.level();
        Optional<Container> at = ContainerAccess.at(level, pos);
        if (at.isEmpty() || !c.companion().hands().canInteract(pos)) {
            return;
        }
        Container container = at.get();
        container.startOpen(c.companion().hands().player());
        try {
            if (pass == Pass.SAME && container.isEmpty()) {
                emptyAtOpen.add(pos);
            }
            Set<String> inside = new HashSet<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    inside.add(idOf(stack));
                }
            }
            SimpleContainer bag = c.companion().inventory();
            for (Map.Entry<Integer, Integer> entry : depositable(c).entrySet()) {
                ItemStack stack = bag.getItem(entry.getKey());
                if (pass == Pass.SAME && !inside.contains(idOf(stack))) {
                    continue;
                }
                ItemStack moving = stack.copyWithCount(entry.getValue());
                ItemStack leftover = ContainerAccess.insert(container, moving);
                int count = entry.getValue() - leftover.getCount();
                if (count > 0) {
                    stack.shrink(count);
                    if (stack.isEmpty()) {
                        bag.setItem(entry.getKey(), ItemStack.EMPTY);
                    }
                    moved += count;
                    used.add(pos);
                }
            }
            bag.setChanged();
            container.setChanged();
            ContainerAccess.recordContents(level, c.companion(), pos, container);
        } finally {
            container.stopOpen(c.companion().hands().player());
        }
    }

    private void next(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
        }
        approach = null;
        index++;
    }

    /** Bag slot to how many of its items go into storage. */
    private Map<Integer, Integer> depositable(TaskContext c) {
        SimpleContainer bag = c.companion().inventory();
        List<KeepPolicy.Slot> slots = new ArrayList<>();
        DropIndex tools = DropIndex.of(c.level());
        MaterialPicker blocks = new MaterialPicker(TaskSettings.pathing);
        for (int i = 0; i < bag.getContainerSize(); i++) {
            ItemStack stack = bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = idOf(stack);
            var tool = tools.toolOf(id);
            var food = stack.get(DataComponents.FOOD);
            double price = -1;
            if (blocks.qualifies(stack) && stack.getItem() instanceof BlockItem item) {
                var state = item.getBlock().defaultBlockState();
                price = MaterialPrices.price(LevelCellView.idOf(item.getBlock()), LevelCellView.tagsOf(state));
            }
            slots.add(new KeepPolicy.Slot(i, id, keptTags(stack), stack.getCount(), tool.kind().name(),
                    tool.tier().ordinal(), food == null ? 0 : food.nutrition(), price));
        }
        Map<Integer, Integer> keep = KeepPolicy.keep(slots, keepItems);
        Map<Integer, Integer> deposit = new java.util.LinkedHashMap<>();
        for (KeepPolicy.Slot slot : slots) {
            int count = slot.count() - keep.getOrDefault(slot.index(), 0);
            if (count > 0) {
                deposit.put(slot.index(), count);
            }
        }
        return deposit;
    }

    /** The {@code #tags} of {@code keepItems} this stack carries, without the {@code #}. */
    private Set<String> keptTags(ItemStack stack) {
        Set<String> tags = new HashSet<>();
        for (String entry : keepItems) {
            if (entry.startsWith("#")) {
                Identifier id = Identifier.tryParse(entry.substring(1));
                if (id != null && stack.is(TagKey.create(Registries.ITEM, id))) {
                    tags.add(entry.substring(1));
                }
            }
        }
        return tags;
    }

    private TaskStatus finish(boolean everything) {
        if (moved == 0) {
            summary = everything ? "nothing to deposit; everything left is kept" : "storage is full";
            return TaskStatus.FAILED;
        }
        summary = "deposited " + moved + " items into " + used.size() + " containers at " + place.name()
                + (everything ? "" : "; storage is full");
        return TaskStatus.SUCCESS;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
        }
    }

    @Override
    public String describe() {
        return place == null ? "depositing loot at storage" : "depositing loot at " + place.name();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
