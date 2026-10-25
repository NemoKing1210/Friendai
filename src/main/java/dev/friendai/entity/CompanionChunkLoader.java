package dev.friendai.entity;

import dev.friendai.config.ServerConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Keeps each loaded companion's chunk ticking and restores their chunks when the server starts. */
public final class CompanionChunkLoader {
    /** Ticket level 33 is a full chunk; 31 is entity-ticking, so a radius counts ticking chunks from 2 further out. */
    private static final int TICKING_OFFSET = ChunkLevel.byStatus(FullChunkStatus.FULL)
            - ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    /** How long an anchor may wait for its companion to appear before it is treated as stale. */
    private static final int RESTORE_GRACE_TICKS = 100;
    /** Runtime-only: never persisted (no {@code FLAG_PERSIST}), so it cannot leak into the world save. */
    private static final TicketType COMPANION_TICKET = new TicketType(
            TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private record Pending(ResourceKey<Level> dimension, ChunkPos chunk, long deadline) {}

    private final MinecraftServer server;
    private final Supplier<ServerConfig> config;
    private final CompanionTicketSet tickets = new CompanionTicketSet();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private long ticks;

    public CompanionChunkLoader(MinecraftServer server, Supplier<ServerConfig> config) {
        this.server = server;
        this.config = config;
    }

    /** Loads the chunk of every saved anchor so its companion wakes up without a player nearby. */
    public void restore() {
        if (!enabled()) {
            return;
        }
        CompanionAnchors anchors = anchors();
        for (Map.Entry<UUID, CompanionAnchors.Anchor> entry : anchors.all().entrySet()) {
            CompanionAnchors.Anchor anchor = entry.getValue();
            ServerLevel level = server.getLevel(anchor.dimension());
            if (level == null) {
                anchors.remove(entry.getKey());
                continue;
            }
            pending.put(entry.getKey(), new Pending(anchor.dimension(), anchor.chunk(), ticks + RESTORE_GRACE_TICKS));
            level.getChunk(anchor.chunk().x(), anchor.chunk().z());
        }
    }

    /** Moves tickets to follow the loaded companions and persists their anchors. */
    public void tick() {
        ticks++;
        CompanionAnchors anchors = anchors();
        Set<CompanionTicketSet.Key> desired = new HashSet<>();
        if (enabled()) {
            CompanionManager manager = CompanionManager.get(server);
            for (CompanionEntity companion : manager.all()) {
                if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) {
                    continue;
                }
                ChunkPos chunk = companion.chunkPosition();
                desired.add(key(level, chunk, radius()));
                anchors.put(companion.ownerUuid(), new CompanionAnchors.Anchor(level.dimension(), chunk));
            }
            Iterator<Map.Entry<UUID, Pending>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Pending> entry = iterator.next();
                if (manager.find(entry.getKey()).isPresent()) {
                    iterator.remove();
                    continue;
                }
                Pending pendingAnchor = entry.getValue();
                ServerLevel level = server.getLevel(pendingAnchor.dimension());
                if (level == null || ticks >= pendingAnchor.deadline()) {
                    anchors.remove(entry.getKey());
                    iterator.remove();
                    continue;
                }
                desired.add(key(level, pendingAnchor.chunk(), radius()));
            }
        } else {
            pending.clear();
        }
        apply(tickets.update(desired));
    }

    /** Drops the owner's anchor; called when the companion is killed or dismissed. */
    public void forget(UUID owner) {
        pending.remove(owner);
        anchors().remove(owner);
    }

    /** The anchor recorded for an owner, for diagnostics and tests. */
    public Optional<CompanionAnchors.Anchor> anchorFor(UUID owner) {
        return anchors().get(owner);
    }

    private void apply(CompanionTicketSet.Change change) {
        for (CompanionTicketSet.Key key : change.add()) {
            ServerLevel level = level(key.dimension());
            if (level != null) {
                level.getChunkSource().addTicketWithRadius(
                        COMPANION_TICKET, ChunkPos.unpack(key.chunk()), key.radius() + TICKING_OFFSET);
            }
        }
        for (CompanionTicketSet.Key key : change.remove()) {
            ServerLevel level = level(key.dimension());
            if (level != null) {
                level.getChunkSource().removeTicketWithRadius(
                        COMPANION_TICKET, ChunkPos.unpack(key.chunk()), key.radius() + TICKING_OFFSET);
            }
        }
    }

    private static CompanionTicketSet.Key key(ServerLevel level, ChunkPos chunk, int radius) {
        return new CompanionTicketSet.Key(level.dimension().identifier().toString(), chunk.pack(), radius);
    }

    private ServerLevel level(String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private boolean enabled() {
        return config.get().companion.chunkLoading.enabled;
    }

    private int radius() {
        return config.get().companion.chunkLoading.radius;
    }

    private CompanionAnchors anchors() {
        return server.getDataStorage().computeIfAbsent(CompanionAnchors.TYPE);
    }
}
