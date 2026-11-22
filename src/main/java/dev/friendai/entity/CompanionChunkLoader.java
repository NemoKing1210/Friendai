package dev.friendai.entity;

import dev.friendai.FriendaiMod;
import dev.friendai.features.companion.BoundedRotation;
import dev.friendai.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

/**
 * Keeps each loaded companion's chunk ticking, restores their chunks when the server starts, and re-arms a saved
 * anchor whose chunk is not in memory yet.
 *
 * <p>Restoration is bounded per tick: a saved anchor is admitted and re-checked by a fair pass that visits at most
 * {@link #ANCHORS_PER_TICK} owners, so a world with hundreds of anchors neither generates nor tickets them all in one
 * server tick. A ticket issued for a waiting anchor is held until its companion appears or the anchor is dropped, so
 * the pass does not add and remove the same ticket every tick. The pass itself is the only O(anchor count) work and
 * runs only when the saved set changes, not on every tick.
 */
public final class CompanionChunkLoader {
    /** Ticket level 33 is a full chunk; 31 is entity-ticking, so a radius counts ticking chunks from 2 further out. */
    private static final int TICKING_OFFSET =
            ChunkLevel.byStatus(FullChunkStatus.FULL) - ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    /** How long an anchor may wait for its chunk to load before it is treated as broken. */
    private static final int LOAD_TIMEOUT_TICKS = 1200;
    /** How long a loaded anchor may wait for its companion to appear before it is treated as stale. */
    private static final int RESTORE_GRACE_TICKS = 100;
    /** How many saved anchors one tick may admit and re-check. */
    public static final int ANCHORS_PER_TICK = 8;
    /** How often an aggregated warning for dropped stale anchors may be written. */
    private static final int STALE_LOG_INTERVAL_TICKS = 600;
    /** Runtime-only: never persisted (no {@code FLAG_PERSIST}), so it cannot leak into the world save. */
    private static final TicketType COMPANION_TICKET = new TicketType(
            TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private record Pending(ResourceKey<Level> dimension, ChunkPos chunk, long deadline) {}

    private final MinecraftServer server;
    private final Supplier<ServerConfig> config;
    private final CompanionTicketSet tickets = new CompanionTicketSet();
    private final ExpeditionChunkHorizon expeditionHorizon;
    /** Armed anchors that still wait for their companion, whether or not their ticket has been issued yet. */
    private final Map<UUID, Pending> pending = new HashMap<>();
    /** The ticket issued for each visited anchor; kept between passes so a waiting anchor does not lose its ticket. */
    private final Map<UUID, CompanionTicketSet.Key> ticketByOwner = new HashMap<>();
    /** The runtime tickets that follow loaded companions; small (one per loaded companion) and rebuilt every tick. */
    private final Map<UUID, CompanionTicketSet.Key> loadedTickets = new HashMap<>();
    /** The exact set of tickets this loader wants, with a reference count per key; it is updated by deltas only. */
    private final Map<CompanionTicketSet.Key, Integer> desired = new HashMap<>();

    private final List<CompanionTicketSet.Key> addQueue = new ArrayList<>();
    private final List<CompanionTicketSet.Key> removeQueue = new ArrayList<>();
    /** The stable rotation that bounds how many owners each tick visits. */
    private final BoundedRotation rotation = new BoundedRotation(ANCHORS_PER_TICK);
    /**
     * The live union of saved anchors and armed pending entries, used as the rotation's input. It is rebuilt only when
     * that membership changes; a loaded companion is removed, so it never consumes a rotation slot.
     */
    private final Set<UUID> sweepKeys = new HashSet<>();
    /** The saved anchor revision the last sweep saw, so a change triggers exactly one rebuild, never a per-tick copy. */
    private long knownRevision = -1;

    private long ticks;
    private boolean stopped;
    private int lastTickAdmitted;
    private long lastTickNanos;
    private int droppedSinceLog;
    private long nextDropLogTick;

    public CompanionChunkLoader(MinecraftServer server, Supplier<ServerConfig> config) {
        this.server = server;
        this.config = config;
        this.expeditionHorizon = new ExpeditionChunkHorizon(
                server,
                () -> config.get().tasks.resources.allowExpeditions && config.get().companion.chunkLoading.enabled);
    }

    /**
     * Marks every saved anchor as waiting for its companion so the tick loop can wake it without a player nearby.
     * This only records the saved anchors; chunk tickets are issued by the bounded pass in {@link #tick()}, so a
     * large save cannot generate or ticket many chunks at once.
     */
    public void restore() {
        if (stopped) return;
        if (!enabled()) {
            return;
        }
        CompanionAnchors anchors = anchors();
        int armed = 0;
        for (Map.Entry<UUID, CompanionAnchors.Anchor> entry : anchors.all().entrySet()) {
            CompanionAnchors.Anchor anchor = entry.getValue();
            if (server.getLevel(anchor.dimension()) == null) {
                anchors.remove(entry.getKey());
                continue;
            }
            if (arm(entry.getKey(), anchor)) {
                armed++;
            }
        }
        if (armed > 0) {
            FriendaiMod.LOGGER.info("Arming {} companion chunk anchor(s) for restore", armed);
        }
    }

    /** Moves tickets to follow the loaded companions and persists their anchors. */
    public void tick() {
        if (stopped) return;
        ticks++;
        lastTickAdmitted = 0;
        long started = System.nanoTime();
        CompanionAnchors anchors = anchors();
        try {
            if (enabled()) {
                CompanionManager manager = CompanionManager.get(server);
                followLoaded(manager, anchors);
                sweep(manager, anchors);
            } else {
                clearRestoration();
            }
        } finally {
            applyChanges();
            expeditionHorizon.tick();
            lastTickNanos = System.nanoTime() - started;
            logDropped();
        }
    }

    /** Drops the owner's anchor; called when the companion is killed or dismissed. */
    public void forget(UUID owner) {
        resolve(owner);
        expeditionHorizon.release(owner);
        anchors().remove(owner);
    }

    public ExpeditionChunkHorizon expeditionHorizon() {
        return expeditionHorizon;
    }

    /** Releases runtime-only tickets while the server levels are still available. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        pending.clear();
        ticketByOwner.clear();
        loadedTickets.clear();
        // Every reference must be released through the delta queues, or stop() would underflow the counts and leak
        // the vanilla tickets it is supposed to drop.
        for (CompanionTicketSet.Key key : new ArrayList<>(desired.keySet())) {
            release(key);
        }
        sweepKeys.clear();
        knownRevision = -1;
        expeditionHorizon.closeAll();
        applyChanges();
    }

    /** The bounded per-tick admission used by this loader. */
    public int anchorsPerTick() {
        return ANCHORS_PER_TICK;
    }

    /** How many saved anchors are waiting for their companion. */
    public int pendingCount() {
        return pending.size();
    }

    /** How many runtime chunk tickets this loader currently holds. */
    public int ticketCount() {
        return tickets.size();
    }

    /** How many owners whose saved anchor already holds its ticket. */
    public int ticketedAnchorCount() {
        return ticketByOwner.size();
    }

    /** How many owners the sweep still has to admit or re-check. */
    public int sweepableCount() {
        return sweepKeys.size();
    }

    /** How many owners the most recent sweep visited; never more than {@link #ANCHORS_PER_TICK}. */
    public int lastTickAdmitted() {
        return lastTickAdmitted;
    }

    /** Wall-clock nanoseconds the most recent tick spent on its own work, for diagnostics and measurements. */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** The anchor recorded for an owner, for diagnostics and tests. */
    public Optional<CompanionAnchors.Anchor> anchorFor(UUID owner) {
        return anchors().get(owner);
    }

    /** Whether the tick loop still waits for an owner's companion and holds its ticket. */
    public boolean awaitingChunk(UUID owner) {
        return pending.containsKey(owner);
    }

    /** Whether the bounded pass already issued the owner's anchor ticket. */
    public boolean hasAnchorTicket(UUID owner) {
        return ticketByOwner.containsKey(owner);
    }

    /** Keeps the ticket of every loaded companion and records its current anchor. */
    private void followLoaded(CompanionManager manager, CompanionAnchors anchors) {
        // Releasing the previous tick's tickets and re-acquiring the same keys cancels out through the delta
        // queues, so a steady tick issues no vanilla call.
        for (CompanionTicketSet.Key ticket : loadedTickets.values()) {
            release(ticket);
        }
        loadedTickets.clear();
        for (CompanionEntity companion : manager.all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null || !(companion.level() instanceof ServerLevel level)) {
                continue;
            }
            CompanionTicketSet.Key ticket = key(level, companion.chunkPosition(), radius());
            loadedTickets.put(owner, ticket);
            anchors.put(owner, new CompanionAnchors.Anchor(level.dimension(), companion.chunkPosition()));
            resolve(owner);
        }
        for (Map.Entry<UUID, CompanionTicketSet.Key> entry : loadedTickets.entrySet()) {
            acquire(entry.getValue());
        }
    }

    /**
     * Visits at most {@link #ANCHORS_PER_TICK} owners in a stable order, continuing after the last one visited. The
     * owner set itself is rebuilt only when the saved anchors change.
     */
    private void sweep(CompanionManager manager, CompanionAnchors anchors) {
        // A revision, not a map copy: a steady tick must not walk every saved anchor just to learn nothing changed.
        if (anchors.revision() != knownRevision) {
            knownRevision = anchors.revision();
            rebuildSweepKeys();
        }
        for (UUID owner : rotation.next(sweepKeys)) {
            lastTickAdmitted++;
            visit(owner, manager, anchors);
        }
    }

    /** Rebuilds the rotation input from the saved anchors and pending entries, skipping loaded owners. */
    private void rebuildSweepKeys() {
        sweepKeys.clear();
        sweepKeys.addAll(anchors().all().keySet());
        sweepKeys.addAll(pending.keySet());
        for (var companion : CompanionManager.get(server).all()) {
            if (companion.ownerUuid() != null) {
                sweepKeys.remove(companion.ownerUuid());
            }
        }
        rotation.invalidateMembership();
    }

    /** One bounded unit of the pass: resolve, admit, re-check or drop exactly one owner's anchor. */
    private void visit(UUID owner, CompanionManager manager, CompanionAnchors anchors) {
        if (manager.find(owner).isPresent()) {
            resolve(owner);
            if (anchors.get(owner).isPresent()) {
                anchors.remove(owner);
            }
            return;
        }
        CompanionAnchors.Anchor anchor = anchors.get(owner).orElse(null);
        if (anchor == null) {
            resolve(owner);
            return;
        }
        ServerLevel level = server.getLevel(anchor.dimension());
        if (level == null) {
            drop(owner, anchors);
            return;
        }
        Pending pendingAnchor = pending.get(owner);
        if (pendingAnchor == null) {
            pendingAnchor = new Pending(anchor.dimension(), anchor.chunk(), ticks + LOAD_TIMEOUT_TICKS);
            pending.put(owner, pendingAnchor);
        }
        // The grace period starts once the chunk is in memory: a cold chunk at a distance can take a while to
        // generate, and dropping its anchor early would lose the companion.
        if (level.getChunkSource()
                                .getChunkNow(
                                        pendingAnchor.chunk().x(),
                                        pendingAnchor.chunk().z())
                        != null
                && pendingAnchor.deadline() > ticks + RESTORE_GRACE_TICKS) {
            pendingAnchor = new Pending(pendingAnchor.dimension(), pendingAnchor.chunk(), ticks + RESTORE_GRACE_TICKS);
            pending.put(owner, pendingAnchor);
        }
        if (ticks >= pendingAnchor.deadline()) {
            drop(owner, anchors);
            droppedSinceLog++;
            return;
        }
        CompanionTicketSet.Key ticket = key(level, pendingAnchor.chunk(), radius());
        if (!ticket.equals(ticketByOwner.get(owner))) {
            CompanionTicketSet.Key previous = ticketByOwner.put(owner, ticket);
            if (previous != null) {
                release(previous);
            }
            acquire(ticket);
        }
    }

    /** Arms one saved anchor, returning whether it was newly armed. */
    private boolean arm(UUID owner, CompanionAnchors.Anchor anchor) {
        if (pending.containsKey(owner)) {
            return false;
        }
        pending.put(owner, new Pending(anchor.dimension(), anchor.chunk(), ticks + LOAD_TIMEOUT_TICKS));
        if (sweepKeys.add(owner)) {
            rotation.invalidateMembership();
        }
        return true;
    }

    /** Drops the owner's anchor, pending entry and ticket. */
    private void drop(UUID owner, CompanionAnchors anchors) {
        anchors.remove(owner);
        resolve(owner);
    }

    /** Forgets the pending entry, the anchor ticket and the sweep slot; the vanilla ticket is released by a delta. */
    private void resolve(UUID owner) {
        pending.remove(owner);
        if (sweepKeys.remove(owner)) {
            rotation.invalidateMembership();
        }
        CompanionTicketSet.Key ticket = ticketByOwner.remove(owner);
        if (ticket != null) {
            release(ticket);
        }
    }

    /** Adds one reference to the desired set; the first reference queues a vanilla ticket. */
    private void acquire(CompanionTicketSet.Key key) {
        int references = desired.merge(key, 1, Integer::sum);
        if (references > 1) {
            return;
        }
        if (removeQueue.remove(key)) {
            return;
        }
        addQueue.add(key);
    }

    /** Drops one reference; the last reference queues the vanilla ticket removal. */
    private void release(CompanionTicketSet.Key key) {
        Integer references = desired.get(key);
        if (references == null) {
            return;
        }
        if (references <= 1) {
            desired.remove(key);
            if (addQueue.remove(key)) {
                return;
            }
            if (!removeQueue.contains(key)) {
                removeQueue.add(key);
            }
            return;
        }
        desired.put(key, references - 1);
    }

    /** Applies the queued deltas to the vanilla ticket source; a steady tick queues nothing. */
    private void applyChanges() {
        if (addQueue.isEmpty() && removeQueue.isEmpty()) {
            return;
        }
        for (CompanionTicketSet.Key key : addQueue) {
            ServerLevel level = level(key.dimension());
            if (level != null) {
                level.getChunkSource()
                        .addTicketWithRadius(
                                COMPANION_TICKET, ChunkPos.unpack(key.chunk()), key.radius() + TICKING_OFFSET);
            }
        }
        for (CompanionTicketSet.Key key : removeQueue) {
            ServerLevel level = level(key.dimension());
            if (level != null) {
                level.getChunkSource()
                        .removeTicketWithRadius(
                                COMPANION_TICKET, ChunkPos.unpack(key.chunk()), key.radius() + TICKING_OFFSET);
            }
        }
        recomputeHeld();
        addQueue.clear();
        removeQueue.clear();
    }

    /** Mirrors the desired keys into the reference-counted set, so ticketCount reports what vanilla holds. */
    private void recomputeHeld() {
        tickets.update(desired.keySet());
    }

    private void clearRestoration() {
        for (CompanionTicketSet.Key ticket : loadedTickets.values()) {
            release(ticket);
        }
        loadedTickets.clear();
        for (UUID owner : new ArrayList<>(ticketByOwner.keySet())) {
            resolve(owner);
        }
        pending.clear();
        sweepKeys.clear();
        knownRevision = -1;
        rotation.invalidateMembership();
    }

    private void logDropped() {
        if (droppedSinceLog > 0 && ticks >= nextDropLogTick) {
            FriendaiMod.LOGGER.warn("Dropped {} stale companion chunk anchor(s)", droppedSinceLog);
            droppedSinceLog = 0;
            nextDropLogTick = ticks + STALE_LOG_INTERVAL_TICKS;
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
