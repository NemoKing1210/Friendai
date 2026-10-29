package dev.friendai.task;

import java.util.Optional;

/** Narrow task-runner operations used by world-feature policies without exposing the server execution coordinator. */
public interface TaskExecutionPort {
    boolean paused();

    boolean suspended();

    String currentToolName();

    Optional<Task> current();

    void interrupt(Task task, String toolName);

    void notice(String text);

    Hold acquireCombatHold();

    interface Hold extends AutoCloseable {
        @Override
        void close();
    }
}
