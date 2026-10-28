package dev.friendai.task.builtin;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.Navigator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.trading.Merchant;

/** Travels to and inspects only the exact loaded trader UUID supplied by the caller. */
public final class InspectTraderTask implements Task {
    private final UUID trader;
    private UUID ownerAtStart;
    private String dimensionAtStart = "";
    private Navigator navigator;
    private TraderRecord result;
    private TaskStatus terminal;
    private String reason = "";
    private int ticks;

    public InspectTraderTask(UUID trader) {
        if (trader == null) throw new IllegalArgumentException("trader UUID is required");
        this.trader = trader;
    }

    public TraderRecord result() {
        return result;
    }

    @Override
    public String type() {
        return "inspect_trader";
    }

    @Override
    public void start(TaskContext context) {
        ownerAtStart = context.companion().ownerUuid();
        dimensionAtStart = context.level().dimension().identifier().toString();
        navigator = TraderTaskNavigation.navigator(context.companion());
        result = null;
        terminal = null;
        reason = "";
        ticks = 0;
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (terminal != null) return terminal;
        try {
            return tickActive(context);
        } catch (RuntimeException failure) {
            return finish(context, TaskStatus.FAILED, "inspection task failed: " + safeMessage(failure));
        }
    }

    private TaskStatus tickActive(TaskContext context) {
        if (++ticks > TraderTaskNavigation.MAX_TASK_TICKS)
            return finish(context, TaskStatus.FAILED, "inspection deadline expired");
        String invalid = TraderTaskNavigation.invalidContext(context, ownerAtStart, dimensionAtStart);
        if (invalid != null) return finish(context, TaskStatus.FAILED, invalid);
        FriendaiServerState serverState =
                FriendaiServerState.get(context.level().getServer());
        Entity target = context.level().getEntity(trader);
        if (target != null && !target.isAlive()) return finish(context, TaskStatus.FAILED, "exact trader is dead");
        if (target != null && !(target instanceof Merchant)) {
            return finish(context, TaskStatus.FAILED, "bound UUID no longer identifies a merchant");
        }
        if (target == null) {
            TraderRecord known =
                    serverState.trading().book(ownerAtStart).trader(trader).orElse(null);
            BlockPos hint = TraderTaskNavigation.lastKnownPosition(known, dimensionAtStart);
            if (hint == null)
                return finish(context, TaskStatus.FAILED, "exact trader has no same-dimension location hint");
            if (!TraderTaskNavigation.inBoundedArea(context.companion(), hint)) {
                return finish(context, TaskStatus.FAILED, "exact trader is outside the 64-block search bound");
            }
            TraderTaskNavigation.aimAt(navigator, hint, context.companion());
            if (TraderTaskNavigation.atHint(context.companion(), hint)) {
                TraderTaskNavigation.stop(navigator, context);
                return TaskStatus.RUNNING;
            }
            if (navigator.tick(context) == Navigator.State.UNREACHABLE) {
                return finish(context, TaskStatus.FAILED, "no safe path to the exact trader's last location");
            }
            return TaskStatus.RUNNING;
        }
        if (!TraderTaskNavigation.inBoundedArea(context.companion(), target)) {
            return finish(context, TaskStatus.FAILED, "exact trader is outside the 64-block search bound");
        }

        TraderTaskNavigation.aimAt(navigator, target, context.companion());
        if (TraderTaskNavigation.inActionReach(context.companion(), target)) {
            result = FriendaiServerState.get(context.level().getServer())
                    .merchantAdapter()
                    .inspect(
                            context.companion(),
                            target,
                            context.level().getServer().overworld().getGameTime())
                    .orElse(null);
            if (result == null)
                return finish(context, TaskStatus.FAILED, "merchant inspection was denied or unavailable");
            return finish(context, TaskStatus.SUCCESS, "");
        }

        if (navigator.tick(context) == Navigator.State.UNREACHABLE) {
            return finish(context, TaskStatus.FAILED, "no safe path to the exact trader");
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(TaskContext context, TaskStatus status, String message) {
        terminal = status;
        reason = message == null ? "" : message;
        try {
            TraderTaskNavigation.stop(navigator, context);
        } catch (RuntimeException cleanupFailure) {
            context.companion().getNavigation().stop();
            context.companion().setPathing(false);
        }
        navigator = null;
        return status;
    }

    @Override
    public void stop(TaskContext context) {
        try {
            TraderTaskNavigation.stop(navigator, context);
        } catch (RuntimeException cleanupFailure) {
            context.companion().getNavigation().stop();
            context.companion().setPathing(false);
        }
        navigator = null;
        if (terminal == null) {
            terminal = TaskStatus.FAILED;
            reason = "inspection stopped";
        }
    }

    @Override
    public String describe() {
        return "inspect trader " + trader;
    }

    @Override
    public String resultSummary() {
        String summary = "trader=" + trader + "; inspected=" + (result != null)
                + (result == null ? "" : "; offers=" + result.offers().size() + "; revision=" + result.revision())
                + (reason.isBlank() ? "" : "; reason=" + reason) + "; ticks=" + ticks;
        return summary.length() <= 600 ? summary : summary.substring(0, 597) + "...";
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 512 ? message : message.substring(0, 509) + "...";
    }
}
