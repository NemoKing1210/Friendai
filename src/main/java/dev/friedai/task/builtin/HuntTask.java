package dev.friedai.task.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.combat.CombatController;
import dev.friedai.task.combat.Threats;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Hunts and kills mobs of a kind near where it started, then collects the drops. */
public final class HuntTask implements Task {
    private final Predicate<EntityType<?>> matcher;
    private final String label;
    private final int count;
    private final int radius;
    private final Set<UUID> skipped = new HashSet<>();
    private final Set<UUID> unreachable = new HashSet<>();

    private Vec3 origin;
    private LivingEntity target;
    private int kills;
    private String summary = "";

    public HuntTask(Predicate<EntityType<?>> matcher, String label, int count, int radius) {
        this.matcher = matcher;
        this.label = label;
        this.count = count;
        this.radius = radius;
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
            if (Threats.isProtected(entity, companion)) {
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
