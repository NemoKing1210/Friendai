package dev.friendai.task;

/** A long-running activity of the companion. {@code describe()} and {@code resultSummary()} are English, LLM-facing. */
public interface Task {
    String type();

    void start(TaskContext c);

    TaskStatus tick(TaskContext c);

    void stop(TaskContext c);

    String describe();

    String resultSummary();

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
}
