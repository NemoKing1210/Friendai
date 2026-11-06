package dev.friendai.features.construction.minecraft.task;

import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.features.construction.minecraft.build.Materials;
import dev.friendai.features.construction.minecraft.build.Placement;
import dev.friendai.features.construction.minecraft.build.Site;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerSession;
import dev.friendai.task.interact.ContainerTransfer;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * The materials a build needs before it touches the site: what is missing, which storage container can supply
 * it, and the one obtain task that gathers what storage could not. All of it runs in the TAKE phase, so a
 * failed supply leaves the world untouched.
 */
final class BuildMaterialSupply {
    /** What the supply needs from the build: its refusal and material facts, and the phase it leaves to. */
    interface Host {
        TaskStatus fail(TaskContext context, PlacementRefusal refusal, boolean siteFailure);

        /** Bounded attempts spent on one cell, used by the refusal a missing material reports. */
        int attempt(BlockPos pos);

        java.util.List<Placement.PlacedSpec> remaining(net.minecraft.server.level.ServerLevel level);

        Site.SiteReport site();

        BlockPos origin();

        String itemId(Item item);

        dev.friendai.foundation.spatial.Pos toPos(BlockPos pos);

        /** Nothing is missing any more: the build may leave the TAKE phase. */
        void materialsReady();

        boolean gather();

        BuildTask.ObtainFactory obtainFactory();
    }

    private final Host host;
    private ContainerSession takeSession;
    private ItemMatcher takeMatcher;
    private int takeCount;
    private BlockPos takeTarget;
    private final Set<BlockPos> exhaustedStorage = new HashSet<>();
    private Task gatherTask;
    private Item gatherItem;
    private int gatherCount;
    /** Materials already asked an obtain task for, so a short result cannot start the same request again. */
    private final Set<Item> gathered = new HashSet<>();

    BuildMaterialSupply(Host host) {
        this.host = host;
    }

    /** The container the current storage session is taking from, or null: the movement debug overlay points there. */
    BlockPos target() {
        return takeTarget;
    }

    /** Stops a storage session or an obtain task the cancellation found running. */
    void stop(TaskContext c) {
        if (takeSession != null) {
            takeSession.stop(c);
        }
        if (gatherTask != null) {
            gatherTask.stop(c);
        }
    }

    TaskStatus tick(TaskContext c) {
        if (takeSession != null) {
            TaskStatus status = takeSession.tick(c, (context, position, container) -> {
                ContainerTransfer.Result result =
                        ContainerTransfer.take(context, position, container, takeMatcher, takeCount);
                return result.status();
            });
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            if (status == TaskStatus.FAILED && takeTarget != null) {
                exhaustedStorage.add(takeTarget);
            }
            takeSession = null;
            takeMatcher = null;
            takeCount = 0;
            takeTarget = null;
            return TaskStatus.RUNNING;
        }
        if (gatherTask != null) {
            TaskStatus status = gatherTask.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String obtainSummary = gatherTask.resultSummary();
            Item item = gatherItem;
            int count = gatherCount;
            gatherTask = null;
            gatherItem = null;
            if (status == TaskStatus.FAILED) {
                return host.fail(
                        c,
                        new PlacementRefusal.OutOfMaterial(
                                host.toPos(host.origin()),
                                host.itemId(item),
                                obtainSummary,
                                host.attempt(host.origin())),
                        true);
            }
            // A gather that reports success but leaves the item short falls through to the usual materials failure;
            // the gathered set keeps the same request from being started again.
            return TaskStatus.RUNNING;
        }
        Map<Item, Integer> missing = missing(c);
        if (missing.isEmpty()) {
            host.materialsReady();
            return TaskStatus.RUNNING;
        }
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            BlockPos container = storageWith(c, entry.getKey());
            if (container == null) {
                continue;
            }
            ItemMatcher matcher = ItemMatcher.parse(host.itemId(entry.getKey())).orElseThrow();
            takeSession = new ContainerSession(
                    container,
                    null,
                    false,
                    () -> "no storage container at " + container,
                    position -> "there is no chest, barrel or other storage at " + position.getX() + " "
                            + position.getY() + " " + position.getZ());
            takeMatcher = matcher;
            takeCount = entry.getValue();
            takeTarget = container;
            return TaskStatus.RUNNING;
        }
        if (host.gather()) {
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                if (!gathered.add(entry.getKey())) {
                    continue;
                }
                gatherItem = entry.getKey();
                gatherCount = entry.getValue();
                gatherTask = host.obtainFactory().create(host.itemId(gatherItem), gatherCount);
                gatherTask.start(c);
                return TaskStatus.RUNNING;
            }
        }
        return host.fail(c, missingRefusal(missing), true);
    }

    Map<Item, Integer> missing(TaskContext c) {
        if (c.canUseCreativeAction(CreativeAction.FREE_MATERIALS)) {
            return Map.of();
        }
        Map<Item, Integer> needed =
                Materials.needed(host.remaining(c.level()), host.site().foundation());
        Map<Item, Integer> have = Materials.carried(c.companion());
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int lack = entry.getValue() - have.getOrDefault(entry.getKey(), 0);
            if (lack > 0) {
                missing.put(entry.getKey(), lack);
            }
        }
        return missing;
    }

    private BlockPos storageWith(TaskContext context, Item item) {
        ItemMatcher matcher = ItemMatcher.parse(host.itemId(item)).orElse(null);
        if (matcher == null) {
            return null;
        }
        for (BlockPos pos : ContainerAccess.known(context, Materials.STORAGE_RADIUS)) {
            if (!exhaustedStorage.contains(pos)) {
                return pos;
            }
        }
        return null;
    }

    private PlacementRefusal missingRefusal(Map<Item, Integer> missing) {
        String first = "unknown";
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            if (detail.length() == 0) {
                first = host.itemId(entry.getKey());
            } else {
                detail.append(", ");
            }
            detail.append(entry.getValue()).append(" × ").append(host.itemId(entry.getKey()));
        }
        return new PlacementRefusal.OutOfMaterial(
                host.toPos(host.origin()), first, "need " + detail, host.attempt(host.origin()));
    }
}
