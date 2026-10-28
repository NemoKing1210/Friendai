package dev.friendai.server.execution;

/** An independently owned pause of execution. Closing one token never releases another owner's hold. */
public interface ExecutionHold extends AutoCloseable {
    @Override
    void close();
}
