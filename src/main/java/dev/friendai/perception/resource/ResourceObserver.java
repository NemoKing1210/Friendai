package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.ResourceObservation;
import dev.friendai.pathing.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Produces only direct, geometrically visible block evidence. */
public final class ResourceObserver {
    private static final int MAX_QUEUED_RAYS = 512;
    private static final int MAX_CHANGED_JOBS = 256;
    private static final double RADIUS = 16.0;
    private static final long SWEEP_INTERVAL = 20;
    private static final int SWEEP_RAYS = 128;
    private static final Vec3[] CARDINALS = {
            new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
            new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)
    };
    private static final BlockPos[] FACES = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 1, 0),
            new BlockPos(0, -1, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };

    private final CompanionEntity companion;
    private final ArrayDeque<PendingRay> queue = new ArrayDeque<>();
    private final Set<BlockPos> queuedTargets = new HashSet<>();
    private String dimension;
    private UUID owner;
    private long nextSweep = Long.MIN_VALUE;
    private int changedQueued;

    public ResourceObserver(CompanionEntity companion) {
        this.companion = companion;
        this.dimension = dimension();
        this.owner = companion.ownerUuid();
    }

    /** Schedules a deterministic, viewpoint-based sweep at most once per second. */
    public void scheduleSweep(long tick) {
        if (tick < nextSweep || !activeLevel()) return;
        nextSweep = tick + SWEEP_INTERVAL;
        Vec3 eye = companion.getEyePosition();
        double rotation = ((companion.getId() * 0.6180339887498949 + tick * 0.017) % 1.0) * Math.PI * 2.0;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (Vec3 direction : CARDINALS) enqueue(eye, eye.add(direction.scale(RADIUS)), null, false, false);
        int sphericalRays = SWEEP_RAYS - CARDINALS.length;
        for (int i = 0; i < sphericalRays; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / sphericalRays;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = i * golden + rotation;
            Vec3 direction = new Vec3(Math.cos(angle) * ring, y, Math.sin(angle) * ring);
            enqueue(eye, eye.add(direction.scale(RADIUS)), null, false, false);
        }
    }

    /** Schedules inspection after a block change; the changed block and its six neighbors are candidates. */
    public void changed(BlockPos pos) {
        if (!activeLevel() || changedQueued >= MAX_CHANGED_JOBS) return;
        inspectTarget(pos, true);
        for (BlockPos face : FACES) {
            if (changedQueued >= MAX_CHANGED_JOBS) break;
            inspectTarget(pos.offset(face), true);
        }
    }

    /** Focuses a direct observation on a position without reading its block identity. */
    public void inspect(BlockPos pos) {
        if (activeLevel()) inspectTarget(pos, false);
    }

    private void inspectTarget(BlockPos target, boolean changed) {
        BlockPos key = target.immutable();
        if (companion.getEyePosition().distanceToSqr(Vec3.atCenterOf(key)) > RADIUS * RADIUS) return;
        if (queue.size() >= MAX_QUEUED_RAYS || !queuedTargets.add(key)) return;
        if (changed && changedQueued >= MAX_CHANGED_JOBS) {
            queuedTargets.remove(key);
            return;
        }
        Vec3 eye = companion.getEyePosition();
        // Aim at the target's center; the visibility job verifies that it is the first opaque hit.
        PendingRay ray = new PendingRay(eye, Vec3.atCenterOf(key), key, changed, dimension);
        if (changed) {
            changedQueued++;
            queue.addFirst(ray);
        } else queue.addFirst(ray);
    }

    private void enqueue(Vec3 from, Vec3 to, BlockPos target, boolean changed, boolean focused) {
        if (queue.size() >= MAX_QUEUED_RAYS) return;
        if (target != null && !queuedTargets.add(target.immutable())) return;
        PendingRay ray = new PendingRay(from, to, target == null ? null : target.immutable(), changed, dimension);
        if (focused) queue.addFirst(ray); else queue.addLast(ray);
    }

    public int queuedJobs() { return queue.size(); }

    public ObservationBatch tick(int rayBudget, int cellBudget, long deadlineNanos) {
        if (!refreshIdentity() || rayBudget <= 0 || cellBudget <= 0) {
            return new ObservationBatch(List.of(), 0, 0, queue.size());
        }
        List<ResourceObservation> observations = new ArrayList<>();
        Set<ResourceObservation.Location> emitted = new HashSet<>();
        int rays = 0;
        int cells = 0;
        while (!queue.isEmpty() && rays < rayBudget && cells < cellBudget && System.nanoTime() < deadlineNanos) {
            PendingRay pending = queue.peekFirst();
            Vec3 eye = companion.getEyePosition();
            if (pending.dimension == null || !pending.dimension.equals(dimension)
                    || pending.origin.distanceToSqr(eye) > 4.0) {
                queue.removeFirst();
                forgetTarget(pending);
                continue;
            }
            if (pending.job == null) {
                ServerLevel level = (ServerLevel) companion.level();
                pending.job = ResourceSight.rayTo(level, pending.origin, pending.end, pending.target);
            }
            int allowance = Math.max(0, cellBudget - cells);
            ResourceSight.Outcome outcome = pending.job.step(allowance);
            cells += pending.job.visitedCellsLastStep();
            if (outcome == ResourceSight.Outcome.PENDING) break;
            queue.removeFirst();
            rays++;
            forgetTarget(pending);
            if (outcome == ResourceSight.Outcome.VISIBLE && pending.origin.distanceToSqr(companion.getEyePosition()) <= 4.0) {
                BlockPos hit = pending.job.hitBlock();
                if (hit != null) {
                    ServerLevel level = (ServerLevel) companion.level();
                    String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit).getBlock()).toString();
                    ResourceObservation.Location location = new ResourceObservation.Location(dimension,
                            new Pos(hit.getX(), hit.getY(), hit.getZ()));
                    if (emitted.add(location)) {
                        BlockPos viewpoint = companion.blockPosition();
                        long observedTick = level.getServer().overworld().getGameTime();
                        observations.add(new ResourceObservation(blockId, location,
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()), observedTick));
                    }
                }
            }
        }
        return new ObservationBatch(List.copyOf(observations), rays, cells, queue.size());
    }

    private void forgetTarget(PendingRay ray) {
        if (ray.target != null) queuedTargets.remove(ray.target);
        if (ray.changed) changedQueued = Math.max(0, changedQueued - 1);
    }

    private boolean refreshIdentity() {
        if (!activeLevel()) return false;
        String currentDimension = dimension();
        UUID currentOwner = companion.ownerUuid();
        if (!currentDimension.equals(dimension) || !java.util.Objects.equals(currentOwner, owner)) {
            queue.clear();
            queuedTargets.clear();
            changedQueued = 0;
            dimension = currentDimension;
            owner = currentOwner;
            nextSweep = Long.MIN_VALUE;
        }
        return true;
    }

    private String dimension() {
        return companion.level() instanceof ServerLevel level ? level.dimension().identifier().toString() : "";
    }

    private boolean activeLevel() { return companion.level() instanceof ServerLevel && !companion.isRemoved(); }

    private static final class PendingRay {
        final Vec3 origin;
        final Vec3 end;
        final BlockPos target;
        final boolean changed;
        final String dimension;
        ResourceSight.Job job;
        PendingRay(Vec3 origin, Vec3 end, BlockPos target, boolean changed, String dimension) {
            this.origin = origin;
            this.end = end;
            this.target = target;
            this.changed = changed;
            this.dimension = dimension;
        }
    }

    public record ObservationBatch(List<ResourceObservation> observations, int raysCompleted, int cellsVisited,
                                   int queuedRemaining) {}
}
