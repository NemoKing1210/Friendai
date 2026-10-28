package dev.friendai.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** One runtime-only, nonblocking chunk ticket for the next leg of each authorized expedition. */
public final class ExpeditionChunkHorizon {
    private static final long TIMEOUT_TICKS = 200;
    private static final TicketType TICKET =
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final MinecraftServer server;
    private final BooleanSupplier enabled;
    private final Map<UUID, Lease> owners = new HashMap<>();
    private final Map<Key, Integer> references = new HashMap<>();

    public ExpeditionChunkHorizon(MinecraftServer server, BooleanSupplier enabled) {
        this.server = server;
        this.enabled = enabled;
    }

    public synchronized Lease request(UUID owner, ServerLevel level, ChunkPos nextLegChunk) {
        if (owner == null || level == null || nextLegChunk == null)
            throw new IllegalArgumentException("owner, level and chunk are required");
        closeOwner(owner);
        if (!enabled.getAsBoolean()) return Lease.disabled(this, owner, level, nextLegChunk);
        Key key = new Key(level.dimension().identifier().toString(), nextLegChunk.pack());
        int refs = references.merge(key, 1, Integer::sum);
        if (refs == 1) level.getChunkSource().addTicketWithRadius(TICKET, nextLegChunk, 2);
        Lease lease = new Lease(
                this, owner, level, nextLegChunk, key, server.overworld().getGameTime() + TIMEOUT_TICKS);
        owners.put(owner, lease);
        return lease;
    }

    public synchronized void tick() {
        if (!enabled.getAsBoolean()) {
            owners.keySet().stream().toList().forEach(this::closeOwner);
            return;
        }
        long now = server.overworld().getGameTime();
        owners.values().stream()
                .filter(lease -> now >= lease.deadline)
                .map(lease -> lease.owner)
                .toList()
                .forEach(this::closeOwner);
    }

    public synchronized void release(UUID owner) {
        closeOwner(owner);
    }

    public synchronized int activeLeases() {
        return owners.size();
    }

    public synchronized int ticketKeys() {
        return references.size();
    }

    private void closeOwner(UUID owner) {
        Lease lease = owners.remove(owner);
        if (lease != null) release(lease);
    }

    private synchronized void release(Lease lease) {
        if (lease.key == null || lease.closed) return;
        lease.closed = true;
        Integer refs = references.get(lease.key);
        if (refs == null || refs <= 1) {
            references.remove(lease.key);
            ServerLevel level = null;
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate.dimension().identifier().toString().equals(lease.key.dimension())) {
                    level = candidate;
                    break;
                }
            }
            if (level != null)
                level.getChunkSource().removeTicketWithRadius(TICKET, ChunkPos.unpack(lease.key.chunk()), 2);
        } else references.put(lease.key, refs - 1);
    }

    private record Key(String dimension, long chunk) {}

    public static final class Lease implements AutoCloseable {
        private final ExpeditionChunkHorizon horizon;
        private final UUID owner;
        private final ServerLevel level;
        private final ChunkPos chunk;
        private final Key key;
        private final long deadline;
        private boolean closed;

        private Lease(
                ExpeditionChunkHorizon horizon, UUID owner, ServerLevel level, ChunkPos chunk, Key key, long deadline) {
            this.horizon = horizon;
            this.owner = owner;
            this.level = level;
            this.chunk = chunk;
            this.key = key;
            this.deadline = deadline;
        }

        private static Lease disabled(ExpeditionChunkHorizon horizon, UUID owner, ServerLevel level, ChunkPos chunk) {
            return new Lease(horizon, owner, level, chunk, null, 0);
        }

        public boolean ready() {
            return !closed && key != null && level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
        }

        public boolean expired() {
            return closed || key == null || horizon.server.overworld().getGameTime() >= deadline;
        }

        public ChunkPos chunk() {
            return chunk;
        }

        @Override
        public void close() {
            if (key == null) {
                closed = true;
                return;
            }
            horizon.release(this);
            synchronized (horizon) {
                horizon.owners.remove(owner, this);
            }
        }
    }
}
