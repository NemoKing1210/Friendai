package dev.friedai.task.plan;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a list of tasks one after another inside the single-task {@link dev.friedai.task.TaskRunner}: each step's
 * task is built when the step begins, a step that fails stops the plan, and a step crash or timeout is contained.
 */
public final class SequenceTask implements Task {
    private static final Logger LOGGER = LoggerFactory.getLogger("friedai/task");

    private final String goal;
    private final List<PlanEntry> entries;
    private final IntSupplier stepTimeoutTicks;
    private final List<String> done = new ArrayList<>();

    private Task active;
    private int index;
    private int elapsed;
    private int stepStart;
    private String summary = "";

    /** @param goal short human description, may be null or blank, then the plan is described without it */
    public SequenceTask(String goal, List<PlanEntry> entries, IntSupplier stepTimeoutTicks) {
        this.goal = goal == null || goal.isBlank() ? null : goal.strip();
        this.entries = entries;
        this.stepTimeoutTicks = stepTimeoutTicks;
    }

    @Override
    public String type() {
        return "run_plan";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (active == null) {
            if (index >= entries.size()) {
                summary = prefix() + "done: " + doneText();
                return TaskStatus.SUCCESS;
            }
            PlanEntry entry = entries.get(index);
            try {
                active = entry.factory().create(c);
                active.start(c);
                stepStart = elapsed;
            } catch (IllegalArgumentException e) {
                return fail(entry.tool(), e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.warn("Plan step {}/{} ({}) crashed while starting", index + 1, entries.size(), entry.tool(), e);
                return fail(entry.tool(), "task crashed: " + e.getClass().getSimpleName());
            }
        }
        elapsed++;
        TaskStatus status;
        try {
            status = active.tick(c);
        } catch (RuntimeException e) {
            LOGGER.warn("Plan step {}/{} ({}) crashed", index + 1, entries.size(), entries.get(index).tool(), e);
            safeStop(active, c);
            return fail(entries.get(index).tool(), "task crashed: " + e.getClass().getSimpleName());
        }
        if (status == TaskStatus.RUNNING && active.hasTimeout() && elapsed - stepStart > stepTimeoutTicks.getAsInt()) {
            safeStop(active, c);
            return fail(entries.get(index).tool(), "timed out");
        }
        if (status == TaskStatus.SUCCESS) {
            done.add(active.resultSummary());
            active = null;
            index++;
            return TaskStatus.RUNNING;
        }
        if (status == TaskStatus.FAILED) {
            return fail(entries.get(index).tool(), active.resultSummary());
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (active != null) {
            safeStop(active, c);
            active = null;
        }
    }

    @Override
    public String describe() {
        if (index >= entries.size()) {
            return prefix() + "done: " + doneText();
        }
        String progress = "step " + (index + 1) + "/" + entries.size();
        return active == null
                ? prefix() + progress + " (" + entries.get(index).tool() + ")"
                : prefix() + progress + ": " + active.describe();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private TaskStatus fail(String tool, String reason) {
        active = null;
        summary = prefix() + "stopped at step " + (index + 1) + "/" + entries.size() + " (" + tool + "): " + reason
                + "; done before: " + doneText();
        return TaskStatus.FAILED;
    }

    private void safeStop(Task task, TaskContext c) {
        try {
            task.stop(c);
        } catch (RuntimeException e) {
            LOGGER.warn("Plan step {}/{} ({}) failed to stop", index + 1, entries.size(), entries.get(index).tool(), e);
        }
    }

    private String prefix() {
        return goal == null ? "plan " : "plan \"" + goal + "\" ";
    }

    private String doneText() {
        if (done.isEmpty()) {
            return "nothing";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < done.size(); i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append(i + 1).append(") ").append(done.get(i));
        }
        return text.toString();
    }
}
