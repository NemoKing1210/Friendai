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
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Produces only direct, geometrically visible block evidence. */
public final class ResourceObserver {
    private static final int MAX_QUEUED_RAYS = 512;
    private static final int MAX_CHANGED_JOBS = 256;
    private static final int DEFAULT_RADIUS = 16;
    private static final long SWEEP_INTERVAL = 20;
    private static final int SWEEP_RAYS = 128;
    private static final int NEAR_FACE_CHECKS_PER_AXIS = 8;
    private static final Vec3[] CARDINALS = {
            new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
            new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)
    };
    private static final BlockPos[] FACES = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 1, 0),
            new BlockPos(0, -1, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };
    private static final int[][] NEAR_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final CompanionEntity companion;
    private final IntSupplier observationRadius;
    private final ArrayDeque<PendingRay> queue = new ArrayDeque<>();
    private final Set<BlockPos> queuedTargets = new HashSet<>();
    private String dimension;
    private UUID owner;
    private long nextSweep = Long.MIN_VALUE;
    private Vec3 lastSweepEye;
    private int changedQueued;

    public ResourceObserver(CompanionEntity companion) {
        this(companion, () -> DEFAULT_RADIUS);
    }

    public ResourceObserver(CompanionEntity companion, IntSupplier observationRadius) {
        this.companion = companion;
        this.observationRadius = observationRadius;
        this.dimension = dimension();
        this.owner = companion.ownerUuid();
    }

    /** Schedules a deterministic, viewpoint-based sweep at most once per second. */
    public int scheduleSweep(long tick) {
        if (!activeLevel()) return 0;
        Vec3 eye = companion.getEyePosition();
        // Pending rays are valid only within two blocks of their source. A moving follower can
        // otherwise spend most of every sweep discarding stale rays and wait another second
        // before scheduling checks from its new viewpoint.
        if (lastSweepEye != null && lastSweepEye.distanceToSqr(eye) > 4.0) {
            clearQueue();
            nextSweep = Long.MIN_VALUE;
        }
        if (tick < nextSweep) return 0;
        nextSweep = tick + SWEEP_INTERVAL;
        lastSweepEye = eye;
        BlockPos center = BlockPos.containing(eye);
        int scheduled = 0;
        double reach = radius();
        // Cardinal rays are the shortest route to current surface evidence, so put them
        // ahead of the broad scan while keeping the scan itself in FIFO order.
        for (Vec3 direction : CARDINALS)
            if (enqueue(eye, eye.add(direction.scale(reach)), null, false, false)) scheduled++;
        // Aim a bounded set of near-field checks at geometric face centers. These targets are
        // selected without reading block identity; ResourceSight emits a block only when it is
        // the first visible obstruction on the ray.
        for (int distance = 1; distance <= NEAR_FACE_CHECKS_PER_AXIS; distance++) {
            if (inspectTarget(center.offset(distance, 0, 0), false)) scheduled++;
            if (inspectTarget(center.offset(-distance, 0, 0), false)) scheduled++;
            if (inspectTarget(center.offset(0, 0, distance), false)) scheduled++;
            if (inspectTarget(center.offset(0, 0, -distance), false)) scheduled++;
        }
        // Trunks are taller than the eye line: inspect the first three body heights and the diagonals so a tree
        // standing beside the companion is not missed by horizontal rays alone.
        BlockPos foot = companion.blockPosition();
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos level = foot.above(dy);
            for (int[] direction : NEAR_DIRECTIONS) {
                if (inspectTarget(level.offset(direction[0] * 3, 0, direction[1] * 3), false)) scheduled++;
            }
        }
        // A short fan in the facing direction catches the surface the companion is walking toward.
        Direction facing = companion.getDirection();
        int forwardX = facing.getStepX();
        int forwardZ = facing.getStepZ();
        int sideX = -forwardZ;
        int sideZ = forwardX;
        for (int dy = 0; dy <= 1; dy++) {
            for (int lateral : new int[]{-1, 1}) {
                for (int distance : new int[]{3, 5}) {
                    if (inspectTarget(foot.above(dy).offset(forwardX * distance + sideX * lateral, 0,
                            forwardZ * distance + sideZ * lateral), false)) scheduled++;
                }
            }
        }
        double rotation = ((companion.getId() * 0.6180339887498949 + tick * 0.017) % 1.0) * Math.PI * 2.0;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        int sphericalRays = SWEEP_RAYS - CARDINALS.length - 4 * NEAR_FACE_CHECKS_PER_AXIS
                - NEAR_DIRECTIONS.length * 3 - 8;
        for (int i = 0; i < sphericalRays; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / sphericalRays;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = i * golden + rotation;
            Vec3 direction = new Vec3(Math.cos(angle) * ring, y, Math.sin(angle) * ring);
            if (enqueue(eye, eye.add(direction.scale(reach)), null, false, false)) scheduled++;
        }
        return scheduled;
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
        if (!activeLevel()) return;
        BlockPos key = pos.immutable();
        if (queuedTargets.contains(key)) {
            PendingRay pending = queue.stream().filter(ray -> key.equals(ray.target)).findFirst().orElse(null);
            if (pending != null) {
                queue.remove(pending);
                queue.addFirst(pending);
            }
            return;
        }
        // Explicit verification must not be starved behind a full passive sweep queue.
        // Evict the oldest tail job; the fresh target is still checked only by ResourceSight.
        while (queue.size() >= MAX_QUEUED_RAYS) {
            PendingRay evicted = queue.removeLast();
            forgetTarget(evicted);
        }
        if (inspectTarget(key, false)) queue.addFirst(queue.removeLast());
    }

    private boolean inspectTarget(BlockPos target, boolean changed) {
        BlockPos key = target.immutable();
        double radius = radius();
        if (companion.getEyePosition().distanceToSqr(Vec3.atCenterOf(key)) > radius * radius) return false;
        if (queue.size() >= MAX_QUEUED_RAYS || !queuedTargets.add(key)) return false;
        if (changed && changedQueued >= MAX_CHANGED_JOBS) {
            queuedTargets.remove(key);
            return false;
        }
        Vec3 eye = companion.getEyePosition();
        // Aim at the target's center; the visibility job verifies that it is the first opaque hit.
        PendingRay ray = new PendingRay(eye, Vec3.atCenterOf(key), key, changed, dimension);
        if (changed) {
            changedQueued++;
            queue.addFirst(ray);
        } else queue.addLast(ray);
        return true;
    }

    private boolean enqueue(Vec3 from, Vec3 to, BlockPos target, boolean changed, boolean focused) {
        if (queue.size() >= MAX_QUEUED_RAYS) return false;
        if (target != null && !queuedTargets.add(target.immutable())) return false;
        PendingRay ray = new PendingRay(from, to, target == null ? null : target.immutable(), changed, dimension);
        if (focused) queue.addFirst(ray); else queue.addLast(ray);
        return true;
    }

    public int queuedJobs() { return queue.size(); }

    private double radius() {
        return Math.max(4, Math.min(64, observationRadius.getAsInt()));
    }

    public ObservationBatch tick(int rayBudget, int cellBudget, long deadlineNanos) {
        if (!refreshIdentity() || rayBudget <= 0 || cellBudget <= 0) {
            return new ObservationBatch(List.of(), List.of(), 0, 0, queue.size());
        }
        List<ResourceObservation> observations = new ArrayList<>();
        List<CaveEntranceObservation> caveEntrances = new ArrayList<>();
        Set<ResourceObservation.Location> emitted = new HashSet<>();
        int rays = 0;
        int cells = 0;
        boolean attemptedWork = false;
        while (!queue.isEmpty() && rays < rayBudget && cells < cellBudget
                && (!attemptedWork || System.nanoTime() < deadlineNanos)) {
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
            attemptedWork = true;
            cells += pending.job.visitedCellsLastStep();
            if (outcome == ResourceSight.Outcome.PENDING) break;
            queue.removeFirst();
            rays++;
            forgetTarget(pending);
            if (outcome == ResourceSight.Outcome.VISIBLE && pending.origin.distanceToSqr(companion.getEyePosition()) <= 4.0) {
                BlockPos hit = pending.job.hitBlock();
                if (hit != null) {
                    ServerLevel level = (ServerLevel) companion.level();
                    long observedTick = level.getServer().overworld().getGameTime();
                    String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit).getBlock()).toString();
                    ResourceObservation.Location location = new ResourceObservation.Location(dimension,
                            new Pos(hit.getX(), hit.getY(), hit.getZ()));
                    if (emitted.add(location)) {
                        BlockPos viewpoint = companion.blockPosition();
                        observations.add(new ResourceObservation(blockId, location,
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()), observedTick));
                    }
                    if (pending.job.unobstructedOpenCorridor()) {
                        List<Pos> openCells = pending.job.openAirCells().stream()
                                .map(cell -> new Pos(cell.getX(), cell.getY(), cell.getZ())).toList();
                        BlockPos eyeCell = BlockPos.containing(pending.origin);
                        CaveEntranceDetector.detect(openCells,
                                        new Pos(eyeCell.getX(), eyeCell.getY(), eyeCell.getZ()),
                                        new Pos(hit.getX(), hit.getY(), hit.getZ()))
                                .ifPresent(entrance -> caveEntrances.add(new CaveEntranceObservation(dimension,
                                        entrance.approach(), entrance.opening(), entrance.interior(), observedTick)));
                    }
                }
            }
        }
        return new ObservationBatch(List.copyOf(observations), List.copyOf(caveEntrances), rays, cells, queue.size());
    }

    private void forgetTarget(PendingRay ray) {
        if (ray.target != null) queuedTargets.remove(ray.target);
        if (ray.changed) changedQueued = Math.max(0, changedQueued - 1);
    }

    private void clearQueue() {
        queue.clear();
        queuedTargets.clear();
        changedQueued = 0;
    }

    private boolean refreshIdentity() {
        if (!activeLevel()) return false;
        String currentDimension = dimension();
        UUID currentOwner = companion.ownerUuid();
        if (!currentDimension.equals(dimension) || !java.util.Objects.equals(currentOwner, owner)) {
            clearQueue();
            dimension = currentDimension;
            owner = currentOwner;
            nextSweep = Long.MIN_VALUE;
            lastSweepEye = null;
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

    public record ObservationBatch(List<ResourceObservation> observations, List<CaveEntranceObservation> caveEntrances,
                                   int raysCompleted, int cellsVisited, int queuedRemaining) {}
}
