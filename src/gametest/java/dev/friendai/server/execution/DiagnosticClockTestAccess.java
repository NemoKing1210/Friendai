package dev.friendai.server.execution;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.diagnostics.DiagnosticClock;

/** Test-only access to the coordinator's diagnostic clock and frozen activation seam. */
public final class DiagnosticClockTestAccess {
    private DiagnosticClockTestAccess() {}

    public static ExecutionCoordinator create(CompanionEntity companion, DiagnosticClock clock) {
        return new ExecutionCoordinator(
                companion, () -> 300, () -> false, () -> false, companion::serverSettings, clock);
    }

    public static void freezeCurrent(ExecutionCoordinator runner) {
        runner.activeTasks().freezeCurrent();
    }
}
