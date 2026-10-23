package dev.friendai.brain;

/** Runs brain work on the thread that owns the world (the server thread in the game). */
public interface BrainScheduler {
    void execute(Runnable r);

    void schedule(Runnable r, long delayMs);

    long nowMillis();
}
