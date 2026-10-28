package dev.friendai.task.plan;

import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a list of tasks one after another inside the single-task {@link dev.friendai.task.TaskRunner}: each step's
 * task is built when the step begins, a step that fails stops the plan, and a step crash or timeout is contained.
 */
public final class SequenceTask implements Task {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");

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

    /** The plan itself, or the tool of the step that is running; a step still waiting in the queue does not match. */
    @Override
    public boolean matches(String name) {
        return type().equals(name)
                || (active != null
                        && index < entries.size()
                        && entries.get(index).tool().equals(name));
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
            LOGGER.warn(
                    "Plan step {}/{} ({}) crashed",
                    index + 1,
                    entries.size(),
                    entries.get(index).tool(),
                    e);
            safeStop(active, c);
            return fail(
                    entries.get(index).tool(), "task crashed: " + e.getClass().getSimpleName());
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

    /** A cancelled plan hands the runner its current step's cleanup, such as a build's scaffold. */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return active == null ? null : active.cleanupAfterCancel(c);
    }

    /**
     * The remaining plan, so a reload continues at the same step. The running step is saved too when it knows how to
     * save itself (a build), so a build keeps the work and the scaffold already in the world.
     */
    @Override
    public String saveData() {
        List<PlanSave.Step> steps = new ArrayList<>(entries.size());
        for (PlanEntry entry : entries) {
            steps.add(new PlanSave.Step(entry.tool(), entry.args()));
        }
        return PlanSave.encode(goal, index, done, active == null ? null : active.saveData(), steps);
    }

    /**
     * A plan read back from a save: it continues at {@code index}, already reported the {@code done} summaries, and
     * resumes {@code active} (a build) instead of starting that step again.
     */
    public static SequenceTask resumed(
            String goal,
            List<PlanEntry> entries,
            int index,
            List<String> done,
            Task active,
            IntSupplier stepTimeoutTicks) {
        SequenceTask plan = new SequenceTask(goal, entries, stepTimeoutTicks);
        plan.index = Math.min(index, entries.size());
        plan.done.addAll(done);
        plan.active = active;
        return plan;
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
    public List<DebugTarget> debugTargets() {
        return active == null ? List.of() : active.debugTargets();
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
            LOGGER.warn(
                    "Plan step {}/{} ({}) failed to stop",
                    index + 1,
                    entries.size(),
                    entries.get(index).tool(),
                    e);
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
