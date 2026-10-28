package dev.friendai.server.agent;

import dev.friendai.agent.turn.BrainScheduler;
import java.util.PriorityQueue;
import net.minecraft.server.MinecraftServer;

/** Runs brain work on the server thread; delayed work is released from the server tick. */
public final class ServerBrainScheduler implements BrainScheduler {
    private record Delayed(long due, long seq, Runnable task) {}

    private final MinecraftServer server;
    private final PriorityQueue<Delayed> queue = new PriorityQueue<>(
            (a, b) -> a.due() != b.due() ? Long.compare(a.due(), b.due()) : Long.compare(a.seq(), b.seq()));
    private long seq;

    public ServerBrainScheduler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void execute(Runnable r) {
        server.execute(r);
    }

    @Override
    public void schedule(Runnable r, long delayMs) {
        queue.add(new Delayed(nowMillis() + delayMs, seq++, r));
    }

    @Override
    public long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Called once per server tick. */
    public void tick() {
        long now = nowMillis();
        while (!queue.isEmpty() && queue.peek().due() <= now) {
            queue.poll().task().run();
        }
    }
}
