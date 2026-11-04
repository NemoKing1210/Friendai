package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.construction.minecraft.task.SpecialStructureTask;
import dev.friendai.features.construction.special.SpecialStructure;
import dev.friendai.features.construction.special.StructureBook;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.features.places.PortalRoutePolicy;
import dev.friendai.features.places.PortalRoutePolicy.PortalLink;
import dev.friendai.features.places.PortalRoutePolicy.Reason;
import dev.friendai.features.places.PortalRoutePolicy.RouteFacts;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.places.PlaceKind;
import dev.friendai.foundation.places.PlaceSource;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.structure.StructureSite;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Travels between the overworld and the nether through the game's own portal code: it walks into a lit portal within 16
 * blocks, or builds and lights a nether portal from carried obsidian and flint and steel when no portal is near, then
 * applies the transition vanilla found. The destination search, the coordinate scaling and the exit platform stay
 * vanilla's: the task never invents a destination or a coordinate.
 *
 * <p>Before leaving it records the source portal as a durable {@link Place} named "return portal". A user-authored
 * place of that name is never rewritten; an automatic one is refreshed. After arrival the trip only succeeds on a safe
 * landing (solid support, not lava and not the void), and then walks to a recorded portal place in the new dimension
 * when one is near, so a later return uses the same anchor. The End is not buildable by this task and is refused.
 */
public final class TravelThroughPortalTask implements Task {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    /** The name of the durable place a trip records so the companion can find its way back. */
    public static final String RETURN_PLACE = "return portal";

    private static final String PORTAL_STRUCTURE = "nether_portal";
    private static final int SEARCH_RADIUS = 16;
    /** How far from the companion a clear portal site is looked for; the scan never leaves loaded chunks. */
    private static final int SITE_RADIUS = 6;

    private static final int OBSIDIAN_COST = 10;
    private static final int PORTAL_PLACE_RADIUS = 2;
    /** The body must overlap the portal block for the game to notice it, so the walk stops inside the frame. */
    private static final double ARRIVE_IN_PORTAL = 0.3;

    private static final int ENTER_TIMEOUT = 400;
    private static final int TRANSIT_TIMEOUT = 420;
    private static final int LANDING_TIMEOUT = 120;
    private static final int OUTBOUND_TIMEOUT = 600;

    /** What the trip is doing this tick; one state at a time keeps the task resumable. */
    private enum Phase {
        PREPARE,
        BUILD,
        ENTER,
        TRANSIT,
        LAND,
        OUTBOUND
    }

    private final String targetDimension;
    private final MoveHelper mover = new MoveHelper(1.0, ARRIVE_IN_PORTAL, false);
    private final MoveHelper outbound = new MoveHelper(1.0, ARRIVE_IN_PORTAL, false);
    private Phase phase = Phase.PREPARE;
    private BlockPos portal;
    private BlockPos walkTarget;
    private SpecialStructureTask builder;
    private boolean builtHere;
    private int phaseTicks;
    private String summary = "";
    private String sourceDimension = "";
    private String outboundName = "";

    public TravelThroughPortalTask(String targetDimension) {
        this.targetDimension = targetDimension == null ? "" : targetDimension.strip();
    }

    @Override
    public String type() {
        return "travel_through_portal";
    }

    /** Whether a portal trip can ever be what the order asked for: only the two worlds this task can cross. */
    public static boolean isSupportedDestination(String dimension) {
        String value = normalize(dimension);
        return value.equals(OVERWORLD) || value.equals(NETHER);
    }

    /** Whether the companion can leave from this dimension by portal; the End has no portal route here. */
    public static boolean isSupportedSource(String dimension) {
        return isSupportedDestination(dimension);
    }

    @Override
    public void start(TaskContext c) {
        mover.reset(c);
        outbound.reset(c);
        phase = Phase.PREPARE;
        phaseTicks = 0;
        portal = null;
        walkTarget = null;
        builder = null;
        builtHere = false;
        outboundName = "";
        summary = "";
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        return switch (phase) {
            case PREPARE -> prepare(c);
            case BUILD -> build(c);
            case ENTER -> enter(c);
            case TRANSIT -> transit(c);
            case LAND -> land(c);
            case OUTBOUND -> walkOut(c);
        };
    }

    /** Reads the facts once, applies the pure route policy and starts the build or the walk it decided on. */
    private TaskStatus prepare(TaskContext c) {
        sourceDimension = dimension(c);
        String target = normalize(targetDimension);
        if (!isSupportedDestination(targetDimension)) {
            summary = "portal travel to " + (target.isEmpty() ? "that dimension" : target)
                    + " is not supported; only the overworld and the nether";
            return TaskStatus.FAILED;
        }
        if (!isSupportedSource(sourceDimension)) {
            summary = "portal travel from " + sourceDimension + " is not supported by this task";
            return TaskStatus.FAILED;
        }
        if (sourceDimension.equals(target)) {
            summary = "already in " + target;
            return TaskStatus.FAILED;
        }
        BlockPos found = nearestPortal(c.level(), c.companion().blockPosition());
        ServerLevel targetLevel = targetLevel(c.level(), target);
        var settings = c.resourceSearchSettings();
        RouteFacts facts = new RouteFacts(
                sourceDimension,
                target,
                found == null ? PortalLink.MISSING : PortalLink.LINKED,
                // Vanilla links an existing destination portal or builds a safe exit platform there, and the landing
                // this adapter actually gets is verified again before success (see land()).
                PortalLink.LINKED,
                targetLevel != null
                        && landingAvailable(
                                c.level(), targetLevel, c.companion().position()),
                // An owner's order carries consent; free time does not, so a self-started trip is refused.
                !c.autonomous(),
                settings.allowExpeditions() && settings.chunkLoadingEnabled(),
                hasMaterials(c.companion()),
                c.companion().ownerUuid() != null);
        return switch (PortalRoutePolicy.decide(facts)) {
            case PortalRoutePolicy.Decision.Travel travel -> {
                portal = found;
                yield recordAndEnter(c);
            }
            case PortalRoutePolicy.Decision.BuildAndLight ignored -> startBuilding(c);
            case PortalRoutePolicy.Decision.Refuse blocked -> refuse(c.companion(), blocked.reason());
        };
    }

    /** One typed summary per policy refusal, naming the single blocker the owner can act on. */
    private TaskStatus refuse(CompanionEntity companion, Reason reason) {
        summary = switch (reason) {
            case UNKNOWN_DIMENSION -> "portal travel to " + targetDimension + " is not a dimension I know";
            case SAME_DIMENSION -> "already in " + normalize(targetDimension);
            case UNSUPPORTED_PAIR ->
                "there is no portal route from " + sourceDimension + " to " + normalize(targetDimension);
            case NO_OWNER_CONSENT ->
                "travelling to another dimension is not something I do on my own time; ask me directly";
            case EXPEDITION_DISABLED -> "portal travel is off because expeditions or chunk loading are disabled";
            case UNSAFE_LANDING -> "the landing in " + normalize(targetDimension) + " is not safe";
            case MISSING_MATERIAL -> missingMaterial(companion);
            case NO_RETURN_ANCHOR -> "I can't record a return portal because I have no owner to keep places for";
        };
        return TaskStatus.FAILED;
    }

    /** The one carried item a portal build is short of, so the model can name what to gather. */
    private String missingMaterial(CompanionEntity companion) {
        int obsidian = carried(companion, Items.OBSIDIAN);
        if (obsidian < OBSIDIAN_COST) {
            return "a nether portal needs " + OBSIDIAN_COST + " minecraft:obsidian and minecraft:flint_and_steel; I"
                    + " carry " + obsidian + " obsidian, so I need minecraft:obsidian";
        }
        return "a nether portal needs " + OBSIDIAN_COST + " minecraft:obsidian and minecraft:flint_and_steel; I am"
                + " missing minecraft:flint_and_steel";
    }

    private TaskStatus startBuilding(TaskContext c) {
        BlockPos site = clearPortalSite(c.level(), c.companion());
        if (site == null) {
            summary = "there is no clear 4 by 5 spot within " + SITE_RADIUS + " blocks to build a nether portal";
            return TaskStatus.FAILED;
        }
        builder = new SpecialStructureTask(portalStructure(), site, Rotation.NONE, 4);
        builder.start(c);
        phase = Phase.BUILD;
        phaseTicks = 0;
        return TaskStatus.RUNNING;
    }

    private TaskStatus build(TaskContext c) {
        TaskStatus status = builder.tick(c);
        if (status == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        String built = builder.resultSummary();
        builder.stop(c);
        builder = null;
        if (status != TaskStatus.SUCCESS) {
            summary = built;
            return TaskStatus.FAILED;
        }
        builtHere = true;
        portal = nearestPortal(c.level(), c.companion().blockPosition());
        if (portal == null) {
            summary = "the portal was lit but I cannot find it";
            return TaskStatus.FAILED;
        }
        return recordAndEnter(c);
    }

    /** Records the return anchor first, then walks in: a trip without a way back must not start. */
    private TaskStatus recordAndEnter(TaskContext c) {
        String refused = recordAnchor(c);
        if (refused != null) {
            summary = refused;
            return TaskStatus.FAILED;
        }
        walkTarget = lowestPortalBlock(c.level(), portal);
        if (walkTarget == null) {
            summary = "the portal is no longer lit";
            return TaskStatus.FAILED;
        }
        mover.reset(c);
        phaseTicks = 0;
        phase = Phase.ENTER;
        return TaskStatus.RUNNING;
    }

    /**
     * Writes the source portal as the durable return anchor. A place the owner typed in is used as it stands; an
     * automatic one is refreshed in place, so the anchor follows the portal without a schema change.
     */
    private String recordAnchor(TaskContext c) {
        CompanionEntity companion = c.companion();
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return "I can't record a return portal without an owner";
        }
        BlockPos lowest = lowestPortalBlock(c.level(), portal);
        if (lowest == null) {
            return "the portal is no longer lit";
        }
        PlaceService places = c.placeService();
        Place existing =
                places.book(companion).find(RETURN_PLACE, sourceDimension).orElse(null);
        if (existing != null
                && (existing.source() == PlaceSource.MANUAL
                        || existing.source() == PlaceSource.COMMAND
                        || !existing.name().equalsIgnoreCase(RETURN_PLACE))) {
            // The owner named this place, or it only answers to the name as an alias: leave it as it stands.
            return null;
        }
        PlaceValues values = new PlaceValues(
                RETURN_PLACE,
                PlaceKind.PORTAL,
                "",
                sourceDimension,
                new Pos(lowest.getX(), lowest.getY(), lowest.getZ()),
                PORTAL_PLACE_RADIUS,
                Set.of(),
                builtHere ? PlaceSource.BUILD : PlaceSource.STRUCTURE,
                owner,
                "",
                1,
                4);
        try {
            places.remember(
                    companion,
                    new Caller(owner, "", AccessLevel.OWNER),
                    values,
                    c.level().getGameTime());
        } catch (IllegalArgumentException rejected) {
            return "I can't record the return portal: " + rejected.getMessage();
        }
        return null;
    }

    private TaskStatus enter(TaskContext c) {
        Vec3 center = new Vec3(walkTarget.getX() + 0.5, walkTarget.getY(), walkTarget.getZ() + 0.5);
        // The final step is into a passable portal cell that the terrain search cannot use as a goal. Once the
        // companion stands next to the frame, move the body directly; vanilla notices the overlap and owns the rest.
        double dx = center.x - c.companion().getX();
        double dz = center.z - c.companion().getZ();
        double dy = center.y - c.companion().getY();
        if (Math.abs(dx) <= 1.6 && Math.abs(dz) <= 1.6 && Math.abs(dy) <= 1.2) {
            mover.stop(c);
            c.companion().setPathing(true);
            c.companion().getMoveControl().setWantedPosition(center.x, center.y, center.z, 1.0);
            Vec3 step = center.subtract(c.companion().position());
            if (center.y - c.companion().getY() > 0.2) {
                // The frame floor is one block above the surrounding ground: rise first, walk in second.
                if (c.companion().onGround()) {
                    c.companion().setDeltaMovement(0, 0.42, 0);
                }
            } else if (step.horizontalDistanceSqr() > 0.01) {
                Vec3 velocity = c.companion().getDeltaMovement();
                c.companion().setDeltaMovement(step.x * 0.25, velocity.y, step.z * 0.25);
                // Collision-aware movement, not a teleport: the portal cell has no collision shape, and the move
                // only advances the last block the path search cannot use as a goal.
                c.companion().move(net.minecraft.world.entity.MoverType.SELF, new Vec3(step.x * 0.2, 0, step.z * 0.2));
            }
            if (c.companion().blockPosition().equals(walkTarget)) {
                c.companion().getMoveControl().setWantedPosition(center.x, center.y, center.z, 0.0);
                phaseTicks = 0;
                phase = Phase.TRANSIT;
            } else if (++phaseTicks > ENTER_TIMEOUT) {
                summary = "can't walk into the portal at " + walkTarget + " from "
                        + c.companion().blockPosition();
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }
        return switch (mover.tick(c, center)) {
            case ARRIVED -> {
                // The body is inside the frame now; vanilla owns the rest.
                mover.stop(c);
                phaseTicks = 0;
                phase = Phase.TRANSIT;
                yield TaskStatus.RUNNING;
            }
            case STUCK -> {
                summary = "can't walk into the portal at " + walkTarget + " from "
                        + c.companion().blockPosition();
                yield TaskStatus.FAILED;
            }
            case MOVING -> {
                if (++phaseTicks > ENTER_TIMEOUT) {
                    summary = "can't walk into the portal at " + walkTarget + " from "
                            + c.companion().blockPosition();
                    yield TaskStatus.FAILED;
                }
                yield TaskStatus.RUNNING;
            }
        };
    }

    private TaskStatus transit(TaskContext c) {
        String now = dimension(c);
        if (now.equals(normalize(targetDimension))) {
            mover.stop(c);
            phaseTicks = 0;
            phase = Phase.LAND;
            return TaskStatus.RUNNING;
        }
        if (!now.equals(sourceDimension)) {
            summary = "the portal moved me to " + now + " instead of " + normalize(targetDimension);
            return TaskStatus.FAILED;
        }
        // The game notices the body in the portal on its next movement tick, so a dimension change is immediate when
        // it happens. Waiting longer than the transit window means the teleport did not take, and the trip fails here
        // rather than relying on the task progress watch.
        if (++phaseTicks > TRANSIT_TIMEOUT) {
            summary = "the portal did not take me to " + normalize(targetDimension) + " from " + sourceDimension;
            return TaskStatus.FAILED;
        }
        if (!(c.level().getBlockState(walkTarget).getBlock()
                instanceof net.minecraft.world.level.block.Portal portal)) {
            summary = "the portal is no longer lit";
            return TaskStatus.FAILED;
        }
        // Use the portal block's own destination search (the same one vanilla's PortalProcessor calls), then the
        // vanilla teleport. Waiting for the block-inside callback is unreliable for a body standing still: the
        // callback only runs while the body moves, and an existing portal cooldown is reset on every overlap.
        net.minecraft.world.level.portal.TeleportTransition transition =
                portal.getPortalDestination((ServerLevel) c.level(), c.companion(), walkTarget);
        if (transition == null) {
            summary = "the portal has no safe destination";
            return TaskStatus.FAILED;
        }
        c.companion().setPortalCooldown();
        c.companion().teleport(transition);
        // The counter is not reset here: a teleport that leaves the body in the source dimension is exactly the case
        // the transit window above exists to catch. Entering the phase and arriving both reset it.
        return TaskStatus.RUNNING;
    }

    private TaskStatus land(TaskContext c) {
        if (safeLanding(c.level(), c.companion().blockPosition())) {
            return startOutbound(c);
        }
        if (++phaseTicks > LANDING_TIMEOUT) {
            summary = "landed in " + dimension(c) + " without safe ground";
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Leaves the portal frame: walks to a recorded portal place in this dimension, then to the nearest portal, and
     * otherwise stays where the game put the companion. Standing inside a portal for the cooldown would send the
     * companion straight back, which is not what the order asked for.
     */
    private TaskStatus startOutbound(TaskContext c) {
        BlockPos spot = outboundSpot(c);
        if (spot == null) {
            summary = "arrived in " + dimension(c);
            return TaskStatus.SUCCESS;
        }
        walkTarget = spot;
        outbound.reset(c);
        phaseTicks = 0;
        phase = Phase.OUTBOUND;
        return TaskStatus.RUNNING;
    }

    private TaskStatus walkOut(TaskContext c) {
        Vec3 center = new Vec3(walkTarget.getX() + 0.5, walkTarget.getY(), walkTarget.getZ() + 0.5);
        return switch (outbound.tick(c, center)) {
            case ARRIVED -> {
                outbound.stop(c);
                summary = "arrived in " + dimension(c) + " and walked to " + outboundName;
                yield TaskStatus.SUCCESS;
            }
            case STUCK -> {
                outbound.stop(c);
                summary = "arrived in " + dimension(c);
                yield TaskStatus.SUCCESS;
            }
            case MOVING -> {
                if (++phaseTicks > OUTBOUND_TIMEOUT) {
                    outbound.stop(c);
                    summary = "arrived in " + dimension(c);
                    yield TaskStatus.SUCCESS;
                }
                yield TaskStatus.RUNNING;
            }
        };
    }

    /** A safe standing spot beside a portal in the new dimension, or null when the companion should stay put. */
    private BlockPos outboundSpot(TaskContext c) {
        ServerLevel level = c.level();
        BlockPos origin = c.companion().blockPosition();
        Place place = nearestPortalPlace(c, level, origin);
        if (place != null) {
            BlockPos spot = portalStandingSpot(
                    level,
                    new BlockPos(
                            place.anchor().x(),
                            place.anchor().y(),
                            place.anchor().z()));
            if (spot != null) {
                outboundName = place.name();
                return spot;
            }
        }
        BlockPos found = nearestPortal(level, origin);
        if (found == null) {
            return null;
        }
        BlockPos spot = portalStandingSpot(level, lowestPortalBlock(level, found));
        if (spot != null) {
            outboundName = "the nearest portal";
        }
        return spot;
    }

    /** The nearest portal place the owner keeps in this dimension, within walking distance of the landing. */
    private Place nearestPortalPlace(TaskContext c, ServerLevel level, BlockPos origin) {
        String dimension = level.dimension().identifier().toString();
        Place best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Place place : c.placeService().book(c.companion()).all()) {
            if (place.kind() != PlaceKind.PORTAL || !place.dimension().equals(dimension)) {
                continue;
            }
            double dx = place.anchor().x() - origin.getX();
            double dy = place.anchor().y() - origin.getY();
            double dz = place.anchor().z() - origin.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= (double) SEARCH_RADIUS * SEARCH_RADIUS && distance < bestDistance) {
                bestDistance = distance;
                best = place;
            }
        }
        return best;
    }

    /** A free cell beside the frame, never inside it: the normal is the portal's horizontal axis. */
    private static BlockPos portalStandingSpot(ServerLevel level, BlockPos portalBlock) {
        if (portalBlock == null) {
            return null;
        }
        BlockState state = level.getBlockState(portalBlock);
        if (!state.is(Blocks.NETHER_PORTAL)) {
            return null;
        }
        Direction.Axis normal =
                state.getValue(NetherPortalBlock.AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != normal) {
                continue;
            }
            BlockPos spot = portalBlock.relative(direction);
            if (standable(level, spot)) {
                return spot;
            }
        }
        return null;
    }

    /** Whether the companion can stand still at this cell without falling, burning or suffocating. */
    private static boolean standable(ServerLevel level, BlockPos spot) {
        if (!level.hasChunkAt(spot)) {
            return false;
        }
        BlockState feet = level.getBlockState(spot);
        BlockState head = level.getBlockState(spot.above());
        BlockPos belowPos = spot.below();
        BlockState below = level.getBlockState(belowPos);
        return (feet.isAir() || feet.canBeReplaced())
                && (head.isAir() || head.canBeReplaced())
                && !feet.is(Blocks.FIRE)
                && !feet.is(Blocks.SOUL_FIRE)
                && feet.getFluidState().isEmpty()
                && below.isFaceSturdy(level, belowPos, Direction.UP);
    }

    /**
     * A bounded walk from the companion over loaded chunks only, choosing the first free portal footprint with a
     * solid floor. The builder re-checks the site, so this is a head start, not a trust.
     */
    private static BlockPos clearPortalSite(ServerLevel level, CompanionEntity companion) {
        BlockPos center = companion.blockPosition();
        SpecialStructure structure = portalStructure();
        AABB own = companion.getBoundingBox();
        for (int radius = 2; radius <= SITE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos origin = center.offset(dx, dy, dz);
                        if (clearFootprint(level, structure, origin, own)) {
                            return origin;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean clearFootprint(ServerLevel level, SpecialStructure structure, BlockPos origin, AABB own) {
        StructureSite site = new StructureSite(origin, Rotation.NONE);
        AABB box = site.box(structure);
        if (!level.hasChunkAt(BlockPos.containing(box.minX, box.minY, box.minZ))
                || !level.hasChunkAt(BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))
                || own.intersects(box)) {
            return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
            BlockState state = level.getBlockState(pos);
            if ((!state.isAir() && !state.canBeReplaced())
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        for (int x = 0; x < (int) (box.maxX - box.minX); x++) {
            for (int z = 0; z < (int) (box.maxZ - box.minZ); z++) {
                BlockPos below =
                        BlockPos.containing(box.minX, box.minY - 1, box.minZ).offset(x, 0, z);
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether the exit the game will hand the companion is safe: an existing portal near the scaled exit is checked
     * where it stands; without one the portal forcer builds an obsidian platform, and the real landing is verified
     * again after arrival.
     */
    private static boolean landingAvailable(ServerLevel source, ServerLevel target, Vec3 position) {
        double scale = DimensionType.getTeleportationScale(source.dimensionType(), target.dimensionType());
        BlockPos approximate =
                target.getWorldBorder().clampToBounds(position.x * scale, position.y, position.z * scale);
        BlockPos existing = nearestPortal(target, approximate);
        return existing == null || safeLanding(target, lowestPortalBlock(target, existing));
    }

    /** Solid support, no lava and no void; standing in the frame itself counts, because the frame floor holds it. */
    private static boolean safeLanding(ServerLevel level, BlockPos feet) {
        if (feet == null
                || feet.getY() <= level.getMinY() + 1
                || feet.getY() >= level.getMaxY() - 1
                || !level.hasChunkAt(feet)) {
            return false;
        }
        BlockState at = level.getBlockState(feet);
        BlockPos belowPos = feet.below();
        BlockState below = level.getBlockState(belowPos);
        if (at.getFluidState().getType() == Fluids.LAVA || below.getFluidState().getType() == Fluids.LAVA) {
            return false;
        }
        if (at.is(Blocks.FIRE) || at.is(Blocks.SOUL_FIRE) || at.is(Blocks.LAVA) || below.is(Blocks.LAVA)) {
            return false;
        }
        if (at.is(Blocks.NETHER_PORTAL) || below.is(Blocks.NETHER_PORTAL)) {
            return true;
        }
        return below.isFaceSturdy(level, belowPos, Direction.UP);
    }

    /**
     * The nearest portal block around {@code origin}. Unloaded columns are skipped: reading a block loads its chunk,
     * so a hunt over a cold area would generate every chunk in the radius on the server thread.
     */
    public static BlockPos nearestPortal(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (!level.hasChunkAt(new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz))) {
                    continue;
                }
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos at = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(at).is(Blocks.NETHER_PORTAL)) {
                        continue;
                    }
                    double distance = at.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = at;
                    }
                }
            }
        }
        return best;
    }

    /** The bottom block of the frame column a portal block belongs to, or null when it is not a portal. */
    private static BlockPos lowestPortalBlock(ServerLevel level, BlockPos portalBlock) {
        if (portalBlock == null || !level.getBlockState(portalBlock).is(Blocks.NETHER_PORTAL)) {
            return null;
        }
        BlockPos lowest = portalBlock;
        while (level.getBlockState(lowest.below()).is(Blocks.NETHER_PORTAL)) {
            lowest = lowest.below();
        }
        return lowest;
    }

    private static SpecialStructure portalStructure() {
        return StructureBook.withBuiltins().get(PORTAL_STRUCTURE).orElseThrow();
    }

    /** The server level for a destination the route supports; null when the server has no such world. */
    private static ServerLevel targetLevel(ServerLevel source, String dimension) {
        ResourceKey<Level> key = switch (normalize(dimension)) {
            case OVERWORLD -> Level.OVERWORLD;
            case NETHER -> Level.NETHER;
            default -> null;
        };
        return key == null ? null : source.getServer().getLevel(key);
    }

    private static boolean hasMaterials(CompanionEntity companion) {
        return carried(companion, Items.OBSIDIAN) >= OBSIDIAN_COST && carried(companion, Items.FLINT_AND_STEEL) >= 1;
    }

    /** Both the inventory and the held stack count, as the structure builder spends from either. */
    private static int carried(CompanionEntity companion, Item item) {
        int count = companion.inventory().countItem(item);
        ItemStack held = companion.getMainHandItem();
        if (held.is(item)) {
            count += held.getCount();
        }
        return count;
    }

    private static String dimension(TaskContext c) {
        return c.level().dimension().identifier().toString();
    }

    private static String normalize(String dimension) {
        if (dimension == null || dimension.isBlank()) return "";
        String value = dimension.strip().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    @Override
    public void stop(TaskContext c) {
        if (builder != null) {
            builder.stop(c);
            builder = null;
        }
        mover.stop(c);
        outbound.stop(c);
        c.companion().getNavigation().stop();
    }

    /**
     * Vanilla moves the companion on the tick its box overlaps the portal block, before the walk reports arrival, so
     * the success path ends with a live navigator holding the body. Drop it like any other finished task.
     */
    @Override
    public void onTerminal(TaskContext c) {
        if (builder != null) {
            builder.stop(c);
            builder = null;
        }
        mover.stop(c);
        outbound.stop(c);
    }

    @Override
    public String describe() {
        return "travelling to " + targetDimension + " through a portal";
    }

    @Override
    public String debugStatus() {
        String state = phase.name().toLowerCase(Locale.ROOT);
        return summary.isEmpty() ? state : state + ": " + summary;
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String saveData() {
        return targetDimension;
    }
}
