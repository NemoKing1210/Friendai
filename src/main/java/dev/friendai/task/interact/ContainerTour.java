package dev.friendai.task.interact;

import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.places.PlaceKind;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Looking through the storage nearby, the way a player opens the chests in their house before going out for something
 * that may already be inside one. Only containers the companion has not opened within the live window are visited;
 * each opening is recorded by {@link ContainerSession} and starts a new live window for that container.
 */
public final class ContainerTour implements Task {
    /** How far around the companion a tour looks for containers. */
    public static final int RADIUS = 16;

    /** At most this many containers per tour, so one order cannot turn into a long walk. */
    public static final int MAX_CONTAINERS = 16;

    private static final int MAX_KINDS = 12;

    /** The radius to look around in when the tour starts, or zero for a fixed list of targets. */
    private final int radius;

    private final Map<String, Integer> seen = new LinkedHashMap<>();
    private List<BlockPos> targets;
    private ContainerSession session;
    private int index;
    private int checked;
    private int skipped;
    private String summary = "";

    /** A tour of the given containers, in this order. */
    public ContainerTour(List<BlockPos> targets) {
        this.radius = 0;
        this.targets = List.copyOf(targets);
    }

    /** A tour of whatever is unchecked within the radius when the tour starts, so a queued tour is not stale. */
    public static ContainerTour around(int radius) {
        return new ContainerTour(Math.max(1, radius));
    }

    private ContainerTour(int radius) {
        this.radius = radius;
        this.targets = List.of();
    }

    /** How far around the companion an automatic tour looks when it is not inside a remembered house or storage. */
    public static final int NEAR_RADIUS = 8;

    /** A house or storage place this close to the companion counts as the home whose chests it looks through. */
    private static final int HOME_REACH = 16;

    /**
     * Storage containers that were not opened within the live window, in a walking order from the companion: nearest
     * first, then nearest to the previous one. They are the containers within the radius, plus those inside a
     * remembered house or storage the companion stands in or next to, so "the chests of our house" are looked through
     * without wandering to every chest in the neighbourhood. Positions are read from the loaded world (a player knows
     * where the chests of their house are); contents are not, until a container is opened.
     */
    public static List<BlockPos> unchecked(TaskContext context, int radius, int max) {
        if (context.companion().ownerUuid() == null) return List.of();
        ServerLevel level = context.level();
        var book = context.ownerKnowledge();
        BlockPos here = context.companion().blockPosition();
        List<BlockPos> found = new ArrayList<>(ContainerAccess.all(level, here, radius));
        for (Place home : homes(context, here)) {
            Pos anchor = home.anchor();
            for (BlockPos pos : ContainerAccess.all(
                    level, new BlockPos(anchor.x(), anchor.y(), anchor.z()), Math.min(64, home.radius() * 2))) {
                if (home.contains(new Pos(pos.getX(), pos.getY(), pos.getZ()))) found.add(pos);
            }
        }
        Set<BlockPos> candidates = new LinkedHashSet<>();
        for (BlockPos pos : found) {
            BlockPos canonical = ContainerAccess.canonicalChest(level, pos);
            if (!ContainerAccess.seenLive(book, level, canonical)) candidates.add(canonical);
        }
        List<BlockPos> order = new ArrayList<>();
        Set<BlockPos> left = new HashSet<>(candidates);
        BlockPos from = context.companion().blockPosition();
        while (!left.isEmpty() && order.size() < max) {
            BlockPos origin = from;
            BlockPos next = left.stream()
                    .min(Comparator.<BlockPos>comparingDouble(origin::distSqr)
                            .thenComparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getZ))
                    .orElseThrow();
            order.add(next);
            left.remove(next);
            from = next;
        }
        return order;
    }

    /** The owner's houses and storage rooms in this dimension that contain the companion or lie right beside it. */
    private static List<Place> homes(TaskContext context, BlockPos here) {
        List<Place> homes = new ArrayList<>();
        String dimension = context.level().dimension().identifier().toString();
        Pos at = new Pos(here.getX(), here.getY(), here.getZ());
        List<Place> places;
        try {
            places = context.placeLookup().all(context.companion());
        } catch (RuntimeException unavailable) {
            return homes;
        }
        for (Place place : places) {
            if (!dimension.equals(place.dimension())
                    || place.kind() != PlaceKind.HOUSE && place.kind() != PlaceKind.STORAGE) continue;
            Pos anchor = place.anchor();
            double dx = anchor.x() - at.x();
            double dz = anchor.z() - at.z();
            if (place.contains(at) || dx * dx + dz * dz <= (double) HOME_REACH * HOME_REACH) homes.add(place);
        }
        return homes;
    }

    @Override
    public String type() {
        return "check_containers";
    }

    @Override
    public void start(TaskContext c) {
        if (radius > 0) targets = unchecked(c, radius, MAX_CONTAINERS);
        index = 0;
        checked = 0;
        skipped = 0;
        seen.clear();
        session = null;
        summary = "";
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (index < targets.size()) {
            BlockPos target = targets.get(index);
            if (session == null) {
                session = new ContainerSession(
                        target,
                        null,
                        true,
                        () -> "",
                        position -> "there is no container at " + position.getX() + " " + position.getY() + " "
                                + position.getZ() + " any more");
            }
            TaskStatus status = session.tick(c, (context, position, container) -> {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        seen.merge(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                                stack.getCount(),
                                Integer::sum);
                    }
                }
                return TaskStatus.SUCCESS;
            });
            if (status == TaskStatus.RUNNING) return TaskStatus.RUNNING;
            if (status == TaskStatus.SUCCESS) checked++;
            else skipped++;
            session.stop(c);
            session = null;
            index++;
            return TaskStatus.RUNNING;
        }
        summary = text();
        return checked > 0 || targets.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    private String text() {
        if (targets.isEmpty()) {
            return "every container within " + (radius > 0 ? radius : RADIUS)
                    + " blocks was opened in the last five minutes and is still in view";
        }
        StringBuilder text = new StringBuilder("checked ")
                .append(checked)
                .append(" of ")
                .append(targets.size())
                .append(targets.size() == 1 ? " container" : " containers")
                .append(" nearby");
        if (skipped > 0) text.append(" (").append(skipped).append(" could not be opened)");
        if (checked == 0) return text.toString();
        if (seen.isEmpty()) return text.append("; they are empty").toString();
        text.append("; they hold ");
        List<Map.Entry<String, Integer>> kinds = new ArrayList<>(seen.entrySet());
        kinds.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (int i = 0; i < Math.min(MAX_KINDS, kinds.size()); i++) {
            if (i > 0) text.append(", ");
            text.append(kinds.get(i).getValue()).append(' ').append(kinds.get(i).getKey());
        }
        if (kinds.size() > MAX_KINDS)
            text.append(" and ").append(kinds.size() - MAX_KINDS).append(" more kinds");
        return text.toString();
    }

    @Override
    public void stop(TaskContext c) {
        if (session != null) session.stop(c);
        session = null;
    }

    @Override
    public String describe() {
        return "checking the containers nearby (" + Math.min(index + 1, Math.max(1, targets.size())) + " of "
                + targets.size() + ")";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public long progressStamp() {
        return index;
    }
}
