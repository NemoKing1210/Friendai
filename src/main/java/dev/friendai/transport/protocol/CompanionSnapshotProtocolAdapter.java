package dev.friendai.transport.protocol;

import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.presentation.companion.CompanionVitals;
import dev.friendai.presentation.companion.QueueRow;
import dev.friendai.protocol.control.CompanionSnapshotView;
import java.util.List;

/** Maps the menu presentation model to and from its engine-owned wire representation. */
public final class CompanionSnapshotProtocolAdapter {
    private CompanionSnapshotProtocolAdapter() {}

    public static CompanionSnapshotView toProtocol(CompanionSnapshot snapshot) {
        List<CompanionSnapshotView.QueueItem> queue = snapshot.queue().stream()
                .map(row -> new CompanionSnapshotView.QueueItem(
                        row.id(),
                        row.planId(),
                        row.planGoal(),
                        row.planStatus(),
                        row.tool(),
                        row.args(),
                        row.state(),
                        row.note(),
                        row.after(),
                        row.attempts(),
                        row.maxAttempts(),
                        row.priority()))
                .toList();
        CompanionVitals vitals = snapshot.vitals();
        return new CompanionSnapshotView(
                snapshot.present(),
                snapshot.operator(),
                snapshot.companionName(),
                snapshot.provider(),
                snapshot.model(),
                snapshot.thinking(),
                snapshot.currentTask(),
                snapshot.history(),
                snapshot.newLog(),
                snapshot.perception(),
                snapshot.toolNames(),
                new CompanionSnapshotView.Vitals(
                        vitals.health(),
                        vitals.maxHealth(),
                        vitals.food(),
                        vitals.inventoryUsed(),
                        vitals.inventorySlots(),
                        vitals.protection(),
                        vitals.style(),
                        vitals.action(),
                        vitals.reason(),
                        vitals.paceMode(),
                        vitals.xpLevel(),
                        vitals.xpProgress()),
                queue,
                snapshot.autoRespawn(),
                snapshot.autoRespawnAvailable(),
                snapshot.creativeMode(),
                snapshot.creativeModeAvailable(),
                snapshot.freeTimeOn(),
                snapshot.freeTimeActivity(),
                snapshot.freeTimeWaiting(),
                snapshot.followMode(),
                snapshot.freeTimeNext(),
                snapshot.freeTimeBudget(),
                snapshot.freeTimeLast(),
                snapshot.freeTimePlannerModel());
    }

    public static CompanionSnapshot fromProtocol(CompanionSnapshotView view) {
        List<QueueRow> queue = view.queue().stream()
                .map(row -> new QueueRow(
                        row.id(),
                        row.planId(),
                        row.planGoal(),
                        row.planStatus(),
                        row.tool(),
                        row.args(),
                        row.state(),
                        row.note(),
                        row.after(),
                        row.attempts(),
                        row.maxAttempts(),
                        row.priority()))
                .toList();
        CompanionSnapshotView.Vitals vitals = view.vitals();
        return new CompanionSnapshot(
                view.present(),
                view.operator(),
                view.companionName(),
                view.provider(),
                view.model(),
                view.thinking(),
                view.currentTask(),
                view.history(),
                view.newLog(),
                view.perception(),
                view.toolNames(),
                new CompanionVitals(
                        vitals.health(),
                        vitals.maxHealth(),
                        vitals.food(),
                        vitals.inventoryUsed(),
                        vitals.inventorySlots(),
                        vitals.protection(),
                        vitals.style(),
                        vitals.action(),
                        vitals.reason(),
                        vitals.paceMode(),
                        vitals.xpLevel(),
                        vitals.xpProgress()),
                queue,
                view.autoRespawn(),
                view.autoRespawnAvailable(),
                view.creativeMode(),
                view.creativeModeAvailable(),
                view.freeTimeOn(),
                view.freeTimeActivity(),
                view.freeTimeWaiting(),
                view.followMode(),
                view.freeTimeNext(),
                view.freeTimeBudget(),
                view.freeTimeLast(),
                view.freeTimePlannerModel());
    }
}
