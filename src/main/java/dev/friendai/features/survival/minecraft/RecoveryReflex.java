package dev.friendai.features.survival.minecraft;

import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.survival.DeathRecord;
import dev.friendai.features.survival.minecraft.task.RecoverDropsTask;
import dev.friendai.task.Task;
import dev.friendai.task.TaskExecutionPort;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Goes back for what the companion dropped when it died.
 *
 * <p>Two rules, one reflex: drops close by are fetched by an idle or following body on its own initiative, and drops
 * further away are fetched only when free time is allowed to work on its own. The runner is interrupted the way a
 * reflex interrupts, so the follow it replaced resumes when the trip ends; the task itself re-checks the dimension,
 * the hazard and the deadline, and clears the record when it gives up.
 */
public final class RecoveryReflex {
    /** The tool name the running recovery task reports, so the reflex does not interrupt its own trip. */
    public static final String TOOL = "recover_drops";
    /** How close the old drops must be for an idle body to fetch them without being told. */
    private static final int NEARBY_RADIUS = 24;
    /** Ticks between two looks, so a failed trip is not retried on every tick. */
    private static final int COOLDOWN_TICKS = 60;

    private final CompanionEntity companion;
    private int cooldown;

    public RecoveryReflex(CompanionEntity companion) {
        this.companion = companion;
    }

    public void tick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!companion.isAlive() || companion.level().isClientSide() || companion.getTarget() != null) {
            return;
        }
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return;
        }
        TaskExecutionPort runner = companion.taskExecution();
        if (runner.paused() || runner.currentToolName().equals(TOOL) || !freeForErrand(runner)) {
            return;
        }
        CompanionCarryData store = CompanionCarryData.get(companion.level().getServer());
        if (store.refusedRecovery(owner)) {
            return;
        }
        Optional<DeathRecord> open = store.openDrops(owner);
        if (open.isEmpty()) {
            return;
        }
        DeathRecord death = open.get();
        if (death.drops().unrecoverable()
                || !death.dimension()
                        .equals(companion.level().dimension().identifier().getPath())) {
            return;
        }
        double away = companion.position().distanceTo(Vec3.atCenterOf(new BlockPos(death.x(), death.y(), death.z())));
        boolean nearby = away <= NEARBY_RADIUS;
        boolean allowedWide = companion.serverSettings().autonomy().enabled();
        if (!nearby && !allowedWide) {
            return;
        }
        runner.interrupt(new RecoverDropsTask(), TOOL);
        cooldown = COOLDOWN_TICKS;
    }

    /** Only an idle body or one that is merely following may be sent on the errand. */
    private boolean freeForErrand(TaskExecutionPort runner) {
        Optional<Task> current = runner.current();
        return current.isEmpty() || current.get().type().equals("follow");
    }
}
