package dev.friedai.task;

/** Live task limits, updated whenever the server config is applied. */
public final class TaskSettings {
    public static volatile int taskTimeoutSec = 300;
    public static volatile boolean defaultFollow = true;
    public static volatile int mineSearchRadius = 32;

    private TaskSettings() {}
}
