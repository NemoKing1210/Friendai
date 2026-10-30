package dev.friendai.task.inventory;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.combat.minecraft.Threats;
import dev.friendai.features.navigation.minecraft.nav.LevelCellView;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.features.navigation.minecraft.nav.Navigator;
import dev.friendai.features.navigation.pathing.DropPoint;
import dev.friendai.features.navigation.pathing.GoalPickup;
import dev.friendai.task.TaskContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Collects the drops around a fixed point the way a player would: it takes what lies at its feet, walks to the rest a
 * few at a time and gives up on a drop that is hazardous, sealed away, or out of room in the inventory.
 *
 * <p>A collector never changes the terrain: the route digs and builds nothing. It never takes a drop the companion
 * threw itself, and it leaves anything lying in fire, lava or a cactus, where the item itself would not survive; a
 * drop still inside the vanilla pickup delay is waited for rather than skipped. A hostile nearby pauses the walking;
 * what lies at its feet is still taken.
 *
 * <p>Tick it once per server tick, from a task or a path executor, on the server thread. It stops walking by itself
 * when it is done, when it runs out of time, or through {@link #stop(TaskContext)}.
 */
public final class DropCollector {
    public enum State {
        COLLECTING,
        DONE
    }

    /** A drop the round meant to take and left on the ground, with where it was last seen. */
    public record Leftover(BlockPos cell, ItemStack stack) {
        public Leftover {
            stack = stack.copy();
        }
    }

    /** How often a new scan for drops runs, in ticks. */
    private static final int SCAN_INTERVAL = 4;
    /** How close a drop must be to be taken without walking. */
    private static final double TOUCH_RADIUS = 1.5;
    /** How many drops one route may aim at; the rest wait for the next scan. */
    private static final int MAX_GOALS = 8;
    /** How long the companion keeps trying to take a drop it has already reached. */
    private static final int ARRIVE_TICKS = 20;
    /** How long one collection round may last before the remaining drops are left on the ground. */
    private static final int MAX_TICKS = 120;
    /** A hostile inside this radius pauses the walking. */
    private static final double THREAT_RADIUS = 8.0;

    private final Vec3 origin;
    private final int radius;
    private final @Nullable ItemMatcher filter;
    private final Set<UUID> entityFilter;
    private final List<ItemStack> receipts = new ArrayList<>();
    private final Set<UUID> blacklist = new HashSet<>();
    /** Every drop in range that may be taken, nearest first. */
    private List<ItemEntity> drops = List.of();
    /** The drops a route may aim at: the ones lying still enough for their position to mean something. */
    private List<ItemEntity> aimed = List.of();

    private List<DropPoint> goalPoints = List.of();
    private List<Leftover> leftovers = List.of();
    private boolean sawIntended;
    private @Nullable Navigator navigator;
    /**
     * Walks the last metre once the path has ended: the path goal is a whole stance volume, so the companion can stop a
     * body-length outside the pickup box and stand there until the round gives up on a drop it could take.
     */
    private @Nullable MoveHelper closer;

    private int ticks;
    private int arrivedTicks;
    private int collected;
    private boolean rescanNow;

    /** @param origin the point the round is about: drops this far from it, once, are its business */
    public DropCollector(Vec3 origin, int radius) {
        this(origin, radius, null);
    }

    /** @param filter when given, only drops matching it are taken; the rest stay on the ground */
    public DropCollector(Vec3 origin, int radius, @Nullable ItemMatcher filter) {
        this(origin, radius, filter, Set.of());
    }

    /** Restricts pickup to exact attributed entity UUIDs while retaining the ordinary pickup safety rules. */
    public DropCollector(Vec3 origin, int radius, @Nullable ItemMatcher filter, Set<UUID> entityFilter) {
        this.origin = origin;
        this.radius = radius;
        this.filter = filter;
        this.entityFilter = Set.copyOf(entityFilter);
    }

    public State tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        collected += collectAtFeet(c, TOUCH_RADIUS);
        if (++ticks > MAX_TICKS) {
            stop(c);
            return State.DONE;
        }
        if (rescanNow || ticks == 1 || ticks % SCAN_INTERVAL == 0 || stale()) {
            scan(c);
            if (drops.isEmpty()) {
                stop(c);
                return State.DONE;
            }
        }
        if (!Threats.hostilesNear(c.level(), companion.position(), THREAT_RADIUS)
                .isEmpty()) {
            resetNavigation(c);
            return State.COLLECTING;
        }
        List<DropPoint> points = dropPoints();
        if (points.isEmpty()) {
            // Drops are there, but none is ready: still falling, or inside the vanilla pickup delay. Wait for one
            // rather than end the round without it or walk to a guess.
            resetNavigation(c);
            return State.COLLECTING;
        }
        if (!points.equals(goalPoints)) {
            goalPoints = points;
            arrivedTicks = 0;
            // The goal is the same volume the pickup uses, so a stance that can take the drop is where it stops.
            navigator(c).setGoal(new GoalPickup(points, TOUCH_RADIUS));
        }
        tickNavigator(c);
        return State.COLLECTING;
    }

    /** How many items this round has moved into the inventory. */
    public int collected() {
        return collected;
    }

    /** Exact stacks successfully moved from this collector's eligible entities into inventory. */
    public List<ItemStack> collectedStacks() {
        return receipts.stream().map(ItemStack::copy).toList();
    }

    public List<Leftover> leftovers() {
        return leftovers.stream().map(l -> new Leftover(l.cell(), l.stack())).toList();
    }

    /** Whether the round ever saw a drop it meant to take: false means the block dropped nothing in reach. */
    public boolean sawIntended() {
        return sawIntended;
    }

    /** Stops the route and the walking. Drops already taken stay taken. */
    public void stop(TaskContext c) {
        if (closer != null) {
            closer.stop(c);
            closer = null;
        }
        if (navigator != null) {
            navigator.stop(c);
            navigator = null;
        }
        goalPoints = List.of();
        leftovers = scanLeftovers(c);
    }

    /**
     * Takes the drops within {@code radius} of the companion without walking: the same rules as a collector's, minus
     * the route. Returns the number of items moved into the inventory.
     */
    public static int pickupAtFeet(TaskContext c, double radius, @Nullable ItemMatcher filter) {
        return pickupAtFeet(c, radius, filter, Set.of());
    }

    public static int pickupAtFeet(TaskContext c, double radius, @Nullable ItemMatcher filter, Set<UUID> entityFilter) {
        return c.companion()
                .collectNearbyItems(
                        radius,
                        e -> !e.hasPickUpDelay()
                                && (entityFilter.isEmpty() || entityFilter.contains(e.getUUID()))
                                && (filter == null || filter.test(e.getItem()))
                                && safe(e));
    }

    private int collectAtFeet(TaskContext c, double radius) {
        CompanionEntity companion = c.companion();
        AABB bounds = companion.getBoundingBox().inflate(radius);
        Map<UUID, ItemStack> before = new java.util.LinkedHashMap<>();
        for (ItemEntity entity : c.level()
                .getEntitiesOfClass(
                        ItemEntity.class,
                        bounds,
                        item -> companion.canCollect(item)
                                && !item.hasPickUpDelay()
                                && (entityFilter.isEmpty() || entityFilter.contains(item.getUUID()))
                                && (filter == null || filter.test(item.getItem()))
                                && safe(item))) {
            before.put(entity.getUUID(), entity.getItem().copy());
        }
        int moved = pickupAtFeet(c, radius, filter, entityFilter);
        for (var entry : before.entrySet()) {
            ItemEntity entity = c.level().getEntity(entry.getKey()) instanceof ItemEntity found ? found : null;
            int taken = entity == null
                    ? entry.getValue().getCount()
                    : Math.max(0, entry.getValue().getCount() - entity.getItem().getCount());
            if (taken > 0) receipts.add(entry.getValue().copyWithCount(taken));
        }
        return moved;
    }

    private void scan(TaskContext c) {
        CompanionEntity companion = c.companion();
        rescanNow = false;
        List<ItemEntity> seen = companion
                .level()
                .getEntitiesOfClass(
                        ItemEntity.class, new AABB(origin, origin).inflate(radius), e -> intended(e, companion));
        if (!seen.isEmpty()) {
            sawIntended = true;
        }
        drops = seen.stream()
                .filter(e -> wanted(e, companion))
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(companion)))
                .toList();
        aimed = drops.stream()
                .filter(e -> !e.hasPickUpDelay() && atRest(e))
                .limit(MAX_GOALS)
                .toList();
    }

    /** A drop this round would take if it could: of the right kind, hazard-free and one the companion may collect. */
    private boolean intended(ItemEntity e, CompanionEntity companion) {
        return companion.canCollect(e)
                && (entityFilter.isEmpty() || entityFilter.contains(e.getUUID()))
                && (filter == null || filter.test(e.getItem()))
                && safe(e);
    }

    /** A drop it can take right now: wanted, not given up on, and with room for it. */
    private boolean wanted(ItemEntity e, CompanionEntity companion) {
        return intended(e, companion)
                && companion.inventory().canAddItem(e.getItem())
                && !blacklist.contains(e.getUUID());
    }

    /** A drop whose position is stable enough to plan a route to. */
    private static boolean atRest(ItemEntity e) {
        return e.onGround() || e.isInWater();
    }

    /**
     * A drop that is not being destroyed where it lies. The companion's own walk stays out of hazard cells; the drop
     * itself only needs the cell not to eat it, so a berry that rested in its bush or a steak on a campfire is taken.
     */
    private static boolean safe(ItemEntity e) {
        return !LevelCellView.destroysItems(e.level().getBlockState(e.blockPosition()));
    }

    private List<DropPoint> dropPoints() {
        List<DropPoint> points = new ArrayList<>(Math.min(MAX_GOALS, aimed.size()));
        for (ItemEntity e : aimed) {
            if (!blacklist.contains(e.getUUID())) {
                Vec3 at = e.position();
                points.add(new DropPoint(at.x, at.y, at.z));
            }
        }
        return points;
    }

    /** The drops still on the ground that this round meant to take, with where they lie now. */
    private List<Leftover> scanLeftovers(TaskContext c) {
        CompanionEntity companion = c.companion();
        List<Leftover> left = new ArrayList<>();
        for (ItemEntity e : c.level()
                .getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(origin, origin).inflate(radius),
                        item -> intended(item, companion) && !item.hasPickUpDelay())) {
            left.add(new Leftover(e.blockPosition(), e.getItem()));
        }
        return List.copyOf(left);
    }

    private boolean stale() {
        return drops.stream().anyMatch(ItemEntity::isRemoved);
    }

    private Navigator navigator(TaskContext context) {
        if (navigator == null) {
            navigator = new Navigator(
                    context, false, context.serverSettings().pathing().withActions(false, false), null);
        }
        return navigator;
    }

    private void tickNavigator(TaskContext c) {
        if (navigator == null) {
            return;
        }
        Navigator.State state = navigator.tick(c);
        if (state == Navigator.State.UNREACHABLE) {
            giveUpOnAll(c);
            return;
        }
        if (state != Navigator.State.ARRIVED) {
            arrivedTicks = 0;
            return;
        }
        if (++arrivedTicks >= ARRIVE_TICKS) {
            giveUpOnReached(c);
            return;
        }
        walkOntoDrop(c);
    }

    /** Closes the gap between the path's goal volume and the pickup box, so a reached drop is actually taken. */
    private void walkOntoDrop(TaskContext c) {
        if (aimed.isEmpty()) {
            return;
        }
        if (closer == null) {
            closer = new MoveHelper(1.0, 0.3, false);
        }
        closer.tick(c, aimed.get(0).position());
    }

    /** No route to any of them: leave them all, and look again for drops that are easier to reach. */
    private void giveUpOnAll(TaskContext c) {
        for (ItemEntity e : aimed) {
            blacklist.add(e.getUUID());
        }
        resetNavigation(c);
    }

    /**
     * The companion stood at a drop and could not take it, so only the drops inside the volume the path accepted give
     * up; the others on the route are still worth walking to. A drop the body could cover was retried for
     * {@link #ARRIVE_TICKS} after walking onto it and is genuinely stuck (buried, or held by a pickup rule).
     */
    private void giveUpOnReached(TaskContext c) {
        CompanionEntity companion = c.companion();
        Vec3 at = companion.position();
        int given = 0;
        for (ItemEntity e : aimed) {
            Vec3 drop = e.position();
            if (e.isRemoved()
                    || GoalPickup.reaches(at.x, at.y, at.z, new DropPoint(drop.x, drop.y, drop.z), TOUCH_RADIUS)) {
                blacklist.add(e.getUUID());
                given++;
            }
        }
        if (given == 0) {
            for (ItemEntity e : aimed) {
                blacklist.add(e.getUUID());
            }
        }
        resetNavigation(c);
    }

    private void resetNavigation(TaskContext c) {
        if (closer != null) {
            closer.stop(c);
            closer = null;
        }
        if (navigator != null) {
            navigator.stop(c);
            navigator = null;
        }
        goalPoints = List.of();
        rescanNow = true;
    }
}
