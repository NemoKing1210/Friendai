package dev.friendai.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkSet;
import dev.friendai.place.PlaceCandidate;
import java.util.List;

/** A long-running activity of the companion. {@code describe()} and {@code resultSummary()} are English, LLM-facing. */
public interface Task {
    String type();

    void start(TaskContext c);

    TaskStatus tick(TaskContext c);

    void stop(TaskContext c);

    String describe();

    String resultSummary();

    /** Concrete block targets for the debug overlay while this task is running; empty when it has none. */
    default List<DebugTarget> debugTargets() {
        return List.of();
    }

    /** Immutable, task-owned snapshot of its complete planned work, when the task exposes one. */
    default List<WorkSet> debugWorkSets() {
        return List.of();
    }

    /** Whether a {@code stop} aimed at this tool name means this task; the runner also matches the name it started it under. */
    default boolean matches(String name) {
        return type().equals(name);
    }

    /** Whether the brain should hear about this task finishing or being cancelled. */
    default boolean emitsEvents() {
        return true;
    }

    /** Open-ended tasks (follow, stay) are exempt from the task timeout. */
    default boolean hasTimeout() {
        return true;
    }

    /** A string {@link TaskFactory#restore} understands, or null if the task is not restored after a reload. */
    default String saveData() {
        return null;
    }

    /**
     * A follow-up task that undoes what a cancel cannot undo at once, such as a build's scaffold. The runner starts it
     * in place of the default activity. Null when the task leaves nothing behind.
     */
    default Task cleanupAfterCancel(TaskContext c) {
        return null;
    }

    /**
     * The place this task noticed while it worked, remembered once the task succeeds; null when there is nothing
     * worth remembering. A task reports the site it acted on, not the companion's position.
     */
    default PlaceCandidate placeCandidate() {
        return null;
    }
}
