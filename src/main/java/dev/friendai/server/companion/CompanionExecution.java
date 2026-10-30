package dev.friendai.server.companion;

import dev.friendai.entity.CompanionEntity;

/** Keeps the companion's per-tick execution order explicit without owning any subsystem behavior. */
public final class CompanionExecution {
    private CompanionExecution() {}

    public static void tick(CompanionEntity companion) {
        companion.combatController().tick();
        companion.fireReflex().tick();
        companion.selfCare().tick();
        companion.executionCoordinator().tick();
    }
}
