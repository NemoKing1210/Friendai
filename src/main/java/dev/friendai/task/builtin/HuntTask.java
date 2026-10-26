package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.combat.CombatController;
import dev.friendai.task.combat.Threats;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Hunts and kills mobs of a kind near where it started, then collects the drops. */
public final class HuntTask implements Task {
    private final Predicate<EntityType<?>> matcher;
    private final String label;
    private final int count;
    private final int radius;
    /** The owner asked for this hunt by name, so villagers and golems are fair game (see {@link Threats}). */
    private final boolean ownerOrdered;
    private final Set<UUID> skipped = new HashSet<>();
    private final Set<UUID> unreachable = new HashSet<>();

    private Vec3 origin;
    private LivingEntity target;
    private int kills;
    private String summary = "";

    public HuntTask(Predicate<EntityType<?>> matcher, String label, int count, int radius) {
        this(matcher, label, count, radius, false);
    }

    public HuntTask(Predicate<EntityType<?>> matcher, String label, int count, int radius, boolean ownerOrdered) {
        this.matcher = matcher;
        this.label = label;
        this.count = count;
        this.radius = radius;
        this.ownerOrdered = ownerOrdered;
    }

    @Override
    public String type() {
        return "attack";
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().position();
        kills = 0;
        skipped.clear();
        unreachable.clear();
        target = null;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        CombatController controller = companion.combatController();
        if (controller.state() == CombatController.State.FIGHTING) {
            return TaskStatus.RUNNING;
        }
        // The kill's drops are on the ground: stay until the controller has walked them into the inventory, so the
        // task never reports a kill whose loot it left behind.
        if (controller.looting()) {
            return TaskStatus.RUNNING;
        }
        if (target != null) {
            if (target.isRemoved() || target.isDeadOrDying()) {
                kills++;
            } else {
                unreachable.add(target.getUUID());
            }
            target = null;
        }
        if (kills >= count) {
            summary = successSummary();
            return TaskStatus.SUCCESS;
        }
        LivingEntity next = nearest(c);
        if (next == null) {
            summary = failureSummary();
            return TaskStatus.FAILED;
        }
        controller.fight(next);
        if (controller.state() == CombatController.State.FIGHTING) {
            target = next;
        }
        return TaskStatus.RUNNING;
    }

    private LivingEntity nearest(TaskContext c) {
        CompanionEntity companion = c.companion();
        List<LivingEntity> nearby = c.level().getEntitiesOfClass(
                LivingEntity.class,
                new AABB(origin, origin).inflate(radius),
                entity -> entity.isAlive() && !entity.isRemoved() && matcher.test(entity.getType()));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : nearby) {
            if (c.level().clip(new ClipContext(companion.getEyePosition(), entity.getBoundingBox().getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, companion)).getType() != HitResult.Type.MISS) continue;
            if (Threats.isProtected(entity, companion, ownerOrdered)) {
                skipped.add(entity.getUUID());
                continue;
            }
            if (unreachable.contains(entity.getUUID())) {
                continue;
            }
            double distance = entity.distanceToSqr(companion);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private String successSummary() {
        return "killed " + kills + " " + label + skippedClause();
    }

    private String failureSummary() {
        String text = kills == 0
                ? "no " + label + " within " + radius + " blocks"
                : "killed " + kills + " of " + count + " " + label + ", no more nearby";
        return text + skippedClause();
    }

    private String skippedClause() {
        return skipped.isEmpty() ? "" : "; skipped " + skipped.size() + " that belong to someone";
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().combatController().stopLooting();
        c.companion().combatController().stopFight();
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "hunting " + count + " " + label;
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
