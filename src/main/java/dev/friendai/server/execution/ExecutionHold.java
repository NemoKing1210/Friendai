package dev.friendai.server.execution;

import dev.friendai.task.TaskExecutionPort;

/** An independently owned pause of execution. Closing one token never releases another owner's hold. */
public interface ExecutionHold extends TaskExecutionPort.Hold {
    @Override
    void close();
}
