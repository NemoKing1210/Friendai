package dev.friendai.task.interact;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.features.knowledge.ContainerWithdrawals;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * One bounded walk through ranked storage candidates for a withdrawal that may not fit in the first container. Each
 * candidate is opened through the shared {@link ContainerSession} lifecycle, the transfer takes toward what is still
 * missing, and the next candidate is tried after a partial or a refusal. A container that cannot serve is left out of
 * the rest of the acquisition; one that was only missing stock is re-read by the opening and its record is refreshed.
 *
 * <p>An explicit target is pinned: one container, no fallback. Without coordinates the caller supplies the ranked
 * order, so the run never searches the world itself beyond the candidates it was given.
 */
public final class ContainerTakeRun {
    /** At most this many containers per withdrawal, so an order cannot become a long walk. */
    public static final int MAX_CANDIDATES = ContainerWithdrawals.MAX_CANDIDATES;

    /** The bounded order a caller resolved; recorded evidence first, visible last resorts second. */
    @FunctionalInterface
    public interface Resolver {
        ContainerAccess.WithdrawalTargets resolve(TaskContext context);
    }

    private static final int MAX_UNUSABLE = 32;
    private static final int MAX_REASONS = 4;

    private final ItemMatcher matcher;
    private final int requested;
    private final BlockPos pinnedTarget;
    private final Resolver resolver;
    private final boolean onlyInsidePlacesInFreeTime;
    private final Supplier<String> noTargetSummary;
    private final Set<Long> unusable;

    private List<BlockPos> targets = List.of();
    private int next;
    private ContainerSession session;
    private BlockPos position;
    private ContainerTransfer.Result lastResult;
    private int remaining;
    private int moved;
    private int containers;
    private boolean started;
    private boolean finished;
    private TaskStatus terminal = TaskStatus.RUNNING;
    private String summary = "";
    private final Map<Long, String> failures = new LinkedHashMap<>();

    public ContainerTakeRun(
            ItemMatcher matcher,
            int count,
            BlockPos pinnedTarget,
            Resolver resolver,
            boolean onlyInsidePlacesInFreeTime,
            Supplier<String> noTargetSummary,
            Set<Long> unusable) {
        this.matcher = matcher;
        this.requested = Math.max(1, count);
        this.pinnedTarget = pinnedTarget;
        this.resolver = resolver;
        this.onlyInsidePlacesInFreeTime = onlyInsidePlacesInFreeTime;
        this.noTargetSummary = noTargetSummary;
        this.unusable = unusable == null ? new LinkedHashSet<>() : unusable;
    }

    public void start(TaskContext c) {
        targets = List.of();
        next = 0;
        session = null;
        position = null;
        lastResult = null;
        remaining = requested;
        moved = 0;
        containers = 0;
        started = false;
        finished = false;
        terminal = TaskStatus.RUNNING;
        summary = "";
        failures.clear();
    }

    public TaskStatus tick(TaskContext c) {
        if (finished) return terminal;
        if (!started) {
            started = true;
            if (!ContainerTransfer.canFit(c.companion(), matcher)) {
                return done(TaskStatus.FAILED, "my inventory is full");
            }
            if (pinnedTarget != null) {
                targets = List.of(pinnedTarget);
            } else {
                ContainerAccess.WithdrawalTargets found = resolver == null
                        ? new ContainerAccess.WithdrawalTargets(List.of(), List.of())
                        : resolver.resolve(c);
                targets = bounded(found);
                if (targets.isEmpty()) {
                    return done(
                            TaskStatus.FAILED,
                            noTargetSummary == null ? "no storage container found" : noTargetSummary.get());
                }
            }
        }
        while (true) {
            if (remaining <= 0) return finish();
            if (session == null) {
                if (next >= targets.size()) return finish();
                position = targets.get(next++);
                lastResult = null;
                session =
                        new ContainerSession(position, null, onlyInsidePlacesInFreeTime, () -> "", this::missingTarget);
            }
            ContainerSession active = session;
            TaskStatus status = active.tick(c, (context, at, container) -> {
                ContainerTransfer.Result result = ContainerTransfer.take(context, at, container, matcher, remaining);
                lastResult = result;
                return result.status();
            });
            if (status == TaskStatus.RUNNING) return TaskStatus.RUNNING;
            ContainerSession.FailureKind kind = active.failureKind();
            String sessionFailure = active.failureSummary();
            active.stop(c);
            session = null;
            if (status == TaskStatus.SUCCESS) {
                int taken = lastResult == null ? 0 : lastResult.moved();
                if (taken > 0) {
                    moved += taken;
                    remaining -= taken;
                    containers++;
                }
                continue;
            }
            failures.putIfAbsent(position.asLong(), reason(position, kind, sessionFailure));
            retire(c, position, kind);
        }
    }

    public void stop(TaskContext c) {
        if (session != null) {
            session.stop(c);
            session = null;
        }
    }

    public String describe() {
        return "taking " + requested + " " + matcher.label() + " from a container";
    }

    public String resultSummary() {
        return summary;
    }

    public String debugStatus() {
        return "take " + matcher.label() + ": " + moved + "/" + requested + " from " + Math.min(next, targets.size())
                + "/" + targets.size() + " containers";
    }

    public List<DebugTarget> debugTargets() {
        BlockPos at = position;
        if (at == null && !targets.isEmpty()) at = targets.get(Math.min(next, targets.size() - 1));
        return at == null
                ? List.of()
                : List.of(new DebugTarget(at.getX(), at.getY(), at.getZ(), DebugAction.USE, "container"));
    }

    private List<BlockPos> bounded(ContainerAccess.WithdrawalTargets found) {
        List<BlockPos> order = new ArrayList<>();
        for (BlockPos pos : found.recorded()) {
            // A current record that names the item is fresh evidence the container can serve, so it is retried.
            unusable.remove(pos.asLong());
            if (!order.contains(pos)) order.add(pos);
        }
        for (BlockPos pos : found.visible()) {
            if (unusable.contains(pos.asLong()) || order.contains(pos)) continue;
            order.add(pos);
        }
        return order.size() <= MAX_CANDIDATES ? List.copyOf(order) : List.copyOf(order.subList(0, MAX_CANDIDATES));
    }

    private TaskStatus finish() {
        if (moved <= 0) {
            if (failures.isEmpty()) {
                return done(
                        TaskStatus.FAILED,
                        noTargetSummary == null ? "no storage container found" : noTargetSummary.get());
            }
            return done(TaskStatus.FAILED, reasons());
        }
        if (containers == 1 && failures.isEmpty() && lastResult != null) {
            return done(TaskStatus.SUCCESS, lastResult.summary());
        }
        String who = containers == 1 ? "a container" : containers + " containers";
        if (remaining > 0) {
            return done(
                    TaskStatus.SUCCESS,
                    "took only " + moved + " of " + requested + " " + matcher.label() + " from " + who);
        }
        return done(TaskStatus.SUCCESS, "took " + moved + " " + matcher.label() + " from " + who);
    }

    private TaskStatus done(TaskStatus status, String text) {
        finished = true;
        terminal = status;
        summary = text;
        return status;
    }

    private String reasons() {
        List<String> unique = new ArrayList<>();
        for (String reason : failures.values()) {
            if (reason != null && !reason.isBlank() && !unique.contains(reason)) unique.add(reason);
            if (unique.size() >= MAX_REASONS) break;
        }
        return String.join("; ", unique);
    }

    private String reason(BlockPos at, ContainerSession.FailureKind kind, String sessionFailure) {
        if (kind != ContainerSession.FailureKind.NONE && sessionFailure != null && !sessionFailure.isBlank()) {
            return sessionFailure;
        }
        if (lastResult != null) return lastResult.summary();
        return "there is no chest, barrel or other storage at " + coords(at);
    }

    private void retire(TaskContext c, BlockPos at, ContainerSession.FailureKind kind) {
        if (pinnedTarget != null) {
            if (kind == ContainerSession.FailureKind.MISSING || kind == ContainerSession.FailureKind.UNLOADED) {
                c.storage().forget(c.companion(), c.level(), at);
            }
            return;
        }
        unusable.add(at.asLong());
        while (unusable.size() > MAX_UNUSABLE) {
            Iterator<Long> oldest = unusable.iterator();
            oldest.next();
            oldest.remove();
        }
        if (kind == ContainerSession.FailureKind.MISSING
                || kind == ContainerSession.FailureKind.UNLOADED
                || kind == ContainerSession.FailureKind.PROTECTED
                || kind == ContainerSession.FailureKind.LOCKED) {
            c.storage().forget(c.companion(), c.level(), at);
        }
    }

    private String missingTarget(BlockPos at) {
        return "there is no chest, barrel or other storage at " + coords(at);
    }

    private static String coords(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
