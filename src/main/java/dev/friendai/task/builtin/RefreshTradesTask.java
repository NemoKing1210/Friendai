package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Inspects a bounded list of exact remembered traders, one task tick at a time. */
public final class RefreshTradesTask implements Task {
    public static final int MAX_TRADERS = 20;

    private final List<UUID> traders;
    private final long deadlineTick;
    private int index;
    private int inspected;
    private InspectTraderTask current;
    private TaskStatus terminal;
    private String reason = "";

    public RefreshTradesTask(List<UUID> traders) {
        this(traders, Long.MAX_VALUE);
    }

    public RefreshTradesTask(List<UUID> traders, long deadlineTick) {
        if (traders == null
                || traders.isEmpty()
                || traders.size() > MAX_TRADERS
                || traders.stream().anyMatch(id -> id == null)
                || new HashSet<>(traders).size() != traders.size()
                || deadlineTick < 0) {
            throw new IllegalArgumentException("refresh needs 1 to 20 distinct trader UUIDs");
        }
        this.traders = List.copyOf(traders);
        this.deadlineTick = deadlineTick;
    }

    @Override
    public String type() {
        return "refresh_trades";
    }

    @Override
    public void start(TaskContext context) {
        index = 0;
        inspected = 0;
        current = null;
        terminal = null;
        reason = "";
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (terminal != null) return terminal;
        if (context.level().getGameTime() >= deadlineTick) {
            reason = "deadline expired before every selected trader was inspected";
            return finish(inspected > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED);
        }
        if (current == null) {
            if (index >= traders.size()) return finish(inspected > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED);
            current = new InspectTraderTask(traders.get(index));
            current.start(context);
        }
        TaskStatus status = current.tick(context);
        if (status == TaskStatus.RUNNING) return status;
        if (status == TaskStatus.SUCCESS) inspected++;
        if (status == TaskStatus.FAILED) {
            if (!reason.isEmpty()) reason += "; ";
            reason += current.resultSummary();
        }
        current = null;
        index++;
        if (index >= traders.size()) return finish(inspected > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED);
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(TaskStatus status) {
        terminal = status;
        return status;
    }

    @Override
    public void stop(TaskContext context) {
        if (current != null) current.stop(context);
        current = null;
        if (terminal == null) {
            terminal = TaskStatus.FAILED;
            reason = "refresh stopped before all selected traders were inspected";
        }
    }

    @Override
    public String describe() {
        return "refresh offers for " + traders.size() + " selected traders";
    }

    @Override
    public String resultSummary() {
        String result =
                "inspected=" + inspected + "/" + traders.size() + (reason.isBlank() ? "" : "; unavailable=" + reason);
        return result.length() <= 600 ? result : result.substring(0, 597) + "...";
    }
}
